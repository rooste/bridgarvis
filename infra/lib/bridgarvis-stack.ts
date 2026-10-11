import * as fs from 'node:fs';
import * as path from 'node:path';
import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as route53 from 'aws-cdk-lib/aws-route53';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as ses from 'aws-cdk-lib/aws-ses';
import * as sesActions from 'aws-cdk-lib/aws-ses-actions';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as snsSubs from 'aws-cdk-lib/aws-sns-subscriptions';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import { Construct } from 'constructs';

export interface BridgarvisStackProps extends cdk.StackProps {
  zoneName: string;
  hostName: string;
  /** Public website (program info, privacy policy, terms) served by nginx on this host. */
  siteHost: string;
  /** Subdomain whose mail is received through SES while the main domain's MX still points elsewhere. */
  mailTestDomain: string;
  /** Mail UI name; nginx passes it through to the gateway (browser client certificates). */
  mailUiHost: string;
  /** The only address the gateway may send email from (e.g. the assistant's address). */
  mailFromAddress: string;
}

/**
 * The Bridgarvis host: one Amazon Linux 2023 (Graviton) instance in its own VPC.
 * No SSH: administration and deploys go through SSM; the app jar comes from the deploy bucket.
 */
export class BridgarvisStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: BridgarvisStackProps) {
    super(scope, id, props);

    const vpc = new ec2.Vpc(this, 'Vpc', {
      ipAddresses: ec2.IpAddresses.cidr('10.20.0.0/16'),
      maxAzs: 1,
      natGateways: 0,
      subnetConfiguration: [{ name: 'public', subnetType: ec2.SubnetType.PUBLIC, cidrMask: 24 }],
    });

    const sg = new ec2.SecurityGroup(this, 'HostSg', {
      vpc,
      description: 'Bridgarvis host',
      allowAllOutbound: true,
    });
    sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(80), 'HTTP (redirect to HTTPS)');
    sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(443), 'HTTPS (website, mail UI)');
    sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(8443), 'Bridgarvis gateway (mTLS)');
    // Port 25 (SMTP) is opened at mail cutover.

    const deployBucket = new s3.Bucket(this, 'DeployBucket', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      versioned: false,
      lifecycleRules: [{ prefix: 'releases/', expiration: cdk.Duration.days(90) }],
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const logGroup = new logs.LogGroup(this, 'Logs', {
      logGroupName: '/bridgarvis',
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const zone = route53.HostedZone.fromLookup(this, 'Zone', { domainName: props.zoneName });

    const role = new iam.Role(this, 'HostRole', {
      assumedBy: new iam.ServicePrincipal('ec2.amazonaws.com'),
      description: 'Bridgarvis host instance role',
      managedPolicies: [iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonSSMManagedInstanceCore')],
    });
    deployBucket.grantRead(role);
    logGroup.grantWrite(role);
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['logs:DescribeLogStreams', 'logs:DescribeLogGroups'],
      resources: ['*'],
    }));
    // certbot DNS-01 validation through Route53, limited to this hosted zone
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['route53:ChangeResourceRecordSets'],
      resources: [zone.hostedZoneArn],
    }));
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['route53:ListHostedZones', 'route53:GetChange'],
      resources: ['*'],
    }));

    const userData = ec2.UserData.forLinux();
    userData.addCommands(
      fs.readFileSync(path.join(import.meta.dirname, '..', 'assets', 'bootstrap.sh'), 'utf8')
        .replaceAll('__LOG_GROUP__', logGroup.logGroupName),
    );

    const instance = new ec2.Instance(this, 'Host', {
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.SMALL),
      // cachedInContext pins the AMI so later deploys don't replace the instance
      machineImage: ec2.MachineImage.latestAmazonLinux2023({
        cpuType: ec2.AmazonLinuxCpuType.ARM_64,
        cachedInContext: true,
      }),
      securityGroup: sg,
      role,
      requireImdsv2: true,
      userData,
      blockDevices: [{
        deviceName: '/dev/xvda',
        volume: ec2.BlockDeviceVolume.ebs(20, { volumeType: ec2.EbsDeviceVolumeType.GP3, encrypted: true }),
      }],
    });

    // Temporary public address; at mail cutover the existing mail Elastic IP moves here instead.
    const eip = new ec2.CfnEIP(this, 'Eip', { domain: 'vpc', tags: [{ key: 'Name', value: 'bridgarvis' }] });
    new ec2.CfnEIPAssociation(this, 'EipAssoc', { allocationId: eip.attrAllocationId, instanceId: instance.instanceId });

    new route53.ARecord(this, 'HostRecord', {
      zone,
      recordName: props.hostName,
      target: route53.RecordTarget.fromIpAddresses(eip.ref),
      ttl: cdk.Duration.minutes(5),
    });
    new route53.ARecord(this, 'SiteRecord', {
      zone,
      recordName: props.siteHost,
      target: route53.RecordTarget.fromIpAddresses(eip.ref),
      ttl: cdk.Duration.minutes(5),
    });

    // ---------------------------------------------------------------- mail (SES receiving)

    // Domain identities with Easy DKIM. The subdomain is verified separately so receiving on it does
    // not depend on how SES treats subdomains of a verified domain.
    // Custom MAIL FROM domain: bounces return under our domain and SPF aligns for DMARC. CDK publishes
    // its MX and SPF records in the zone.
    new ses.EmailIdentity(this, 'DomainIdentity', {
      identity: ses.Identity.publicHostedZone(zone),
      mailFromDomain: `bounce.${props.zoneName}`,
    });
    // Domain policies: only SES may send for the apex; DMARC starts in monitoring mode (p=none) with
    // aggregate reports delivered to our own mailbox (visible in the mail UI).
    new route53.TxtRecord(this, 'ApexSpf', {
      zone, values: ['v=spf1 include:amazonses.com ~all'], ttl: cdk.Duration.hours(1),
    });
    new route53.TxtRecord(this, 'Dmarc', {
      zone, recordName: `_dmarc.${props.zoneName}`,
      values: [`v=DMARC1; p=none; rua=mailto:dmarc-reports@${props.zoneName}; fo=1`],
      ttl: cdk.Duration.hours(1),
    });
    const testIdentity = new ses.EmailIdentity(this, 'TestDomainIdentity', {
      identity: ses.Identity.domain(props.mailTestDomain),
    });
    [
      [testIdentity.dkimDnsTokenName1, testIdentity.dkimDnsTokenValue1],
      [testIdentity.dkimDnsTokenName2, testIdentity.dkimDnsTokenValue2],
      [testIdentity.dkimDnsTokenName3, testIdentity.dkimDnsTokenValue3],
    ].forEach(([name, value], i) => new route53.CnameRecord(this, `TestDkim${i + 1}`, {
      // trailing dot: the token name is already fully qualified (otherwise the zone is appended again)
      zone, recordName: `${name}.`, domainName: value, ttl: cdk.Duration.minutes(30),
    }));
    new route53.MxRecord(this, 'TestMx', {
      zone,
      recordName: props.mailTestDomain,
      values: [{ priority: 10, hostName: `inbound-smtp.${this.region}.amazonaws.com` }],
      ttl: cdk.Duration.minutes(5),
    });

    // Raw mail as SES wrote it (inbound/), parsed text and attachments (parsed/). Kept at most one
    // year, as the privacy policy promises.
    const mailBucket = new s3.Bucket(this, 'MailBucket', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      lifecycleRules: [{ expiration: cdk.Duration.days(365) }],
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    // One item per message and mailbox. box = "<channel>#<address in its exact case>", id = ULID.
    const messages = new dynamodb.TableV2(this, 'Messages', {
      partitionKey: { name: 'box', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'id', type: dynamodb.AttributeType.STRING },
      billing: dynamodb.Billing.onDemand(),
      timeToLiveAttribute: 'expiresAt',
      pointInTimeRecoverySpecification: { pointInTimeRecoveryEnabled: true },
      deletionProtection: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      globalSecondaryIndexes: [
        { indexName: 'byTime', partitionKey: { name: 'channel', type: dynamodb.AttributeType.STRING },
          sortKey: { name: 'id', type: dynamodb.AttributeType.STRING } },
        // Sparse: only items waiting for Juujarvis carry pendingFor
        { indexName: 'pending', partitionKey: { name: 'pendingFor', type: dynamodb.AttributeType.STRING },
          sortKey: { name: 'id', type: dynamodb.AttributeType.STRING } },
      ],
    });

    // SES saves to S3, then notifies SNS -> SQS; the gateway consumes the queue.
    const parseDlq = new sqs.Queue(this, 'MailParseDlq', {
      retentionPeriod: cdk.Duration.days(14),
      enforceSSL: true,
    });
    const parseQueue = new sqs.Queue(this, 'MailParseQueue', {
      retentionPeriod: cdk.Duration.days(14),
      visibilityTimeout: cdk.Duration.minutes(5),
      deadLetterQueue: { queue: parseDlq, maxReceiveCount: 5 },
      enforceSSL: true,
    });
    const received = new sns.Topic(this, 'MailReceived');
    received.addSubscription(new snsSubs.SqsSubscription(parseQueue, { rawMessageDelivery: true }));
    received.addToResourcePolicy(new iam.PolicyStatement({
      principals: [new iam.ServicePrincipal('ses.amazonaws.com')],
      actions: ['sns:Publish'],
      resources: [received.topicArn],
      conditions: { StringEquals: { 'aws:SourceAccount': this.account } },
    }));

    new ses.ReceiptRuleSet(this, 'ReceiptRules', {
      receiptRuleSetName: 'bridgarvis',
      rules: [{
        receiptRuleName: 'test-domain',
        // Main domain (MX switched from the old mail server) and the test subdomain
        recipients: [props.zoneName, props.mailTestDomain],
        scanEnabled: true,
        actions: [new sesActions.S3({ bucket: mailBucket, objectKeyPrefix: 'inbound/', topic: received })],
      }],
    });
    // Note: CloudFormation cannot activate a rule set; activate once with
    //   aws ses set-active-receipt-rule-set --rule-set-name bridgarvis

    // Main-domain MX → SES, and the mail UI name. Adopted (cdk import) from the retired mail-server stack.
    new route53.MxRecord(this, 'MainMx', {
      zone,
      values: [{ priority: 10, hostName: `inbound-smtp.${this.region}.amazonaws.com` }],
      ttl: cdk.Duration.minutes(5),
    });
    new route53.ARecord(this, 'MailUiRecord', {
      zone,
      recordName: props.mailUiHost,
      target: route53.RecordTarget.fromIpAddresses(eip.ref),
      ttl: cdk.Duration.minutes(5),
    });

    // ---------------------------------------------------------------- mail (SES sending)

    // Delivery events (bounce, complaint, delivery, ...) for everything the gateway sends.
    const sendEventsDlq = new sqs.Queue(this, 'MailEventsDlq', { retentionPeriod: cdk.Duration.days(14), enforceSSL: true });
    const sendEventsQueue = new sqs.Queue(this, 'MailEventsQueue', {
      retentionPeriod: cdk.Duration.days(14),
      visibilityTimeout: cdk.Duration.minutes(2),
      deadLetterQueue: { queue: sendEventsDlq, maxReceiveCount: 5 },
      enforceSSL: true,
    });
    const sendEvents = new sns.Topic(this, 'MailEvents');
    sendEvents.addSubscription(new snsSubs.SqsSubscription(sendEventsQueue, { rawMessageDelivery: true }));
    const configurationSet = new ses.ConfigurationSet(this, 'SendConfig', {
      configurationSetName: 'bridgarvis',
      suppressionReasons: ses.SuppressionReasons.BOUNCES_AND_COMPLAINTS,
    });
    configurationSet.addEventDestination('ToSns', {
      destination: ses.EventDestination.snsTopic(sendEvents),
      events: [
        ses.EmailSendingEvent.BOUNCE, ses.EmailSendingEvent.COMPLAINT, ses.EmailSendingEvent.DELIVERY,
        ses.EmailSendingEvent.REJECT, ses.EmailSendingEvent.DELIVERY_DELAY, ses.EmailSendingEvent.RENDERING_FAILURE,
      ],
    });

    // Gateway access
    messages.grantReadWriteData(role);
    mailBucket.grantRead(role, 'inbound/*');
    mailBucket.grantDelete(role, 'inbound/*');
    mailBucket.grantReadWrite(role, 'parsed/*');
    parseQueue.grantConsumeMessages(role);
    sendEventsQueue.grantConsumeMessages(role);
    // Send only from the one address, only with our configuration set (so every send produces events).
    // identity/* because in the SES sandbox the recipients' verified identities are checked too; the
    // FromAddress condition is what limits the sender.
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['ses:SendEmail', 'ses:SendRawEmail'],
      resources: [
        `arn:aws:ses:${this.region}:${this.account}:identity/*`,
        `arn:aws:ses:${this.region}:${this.account}:configuration-set/${configurationSet.configurationSetName}`,
      ],
      conditions: { StringEquals: { 'ses:FromAddress': props.mailFromAddress } },
    }));

    new cdk.CfnOutput(this, 'MessagesTable', { value: messages.tableName });
    new cdk.CfnOutput(this, 'MailBucketName', { value: mailBucket.bucketName });
    new cdk.CfnOutput(this, 'MailParseQueueUrl', { value: parseQueue.queueUrl });
    new cdk.CfnOutput(this, 'MailParseDlqUrl', { value: parseDlq.queueUrl });
    new cdk.CfnOutput(this, 'MailEventsQueueUrl', { value: sendEventsQueue.queueUrl });
    new cdk.CfnOutput(this, 'MailConfigurationSet', { value: configurationSet.configurationSetName });

    new cdk.CfnOutput(this, 'InstanceId', { value: instance.instanceId });
    new cdk.CfnOutput(this, 'PublicIp', { value: eip.ref });
    new cdk.CfnOutput(this, 'DeployBucketName', { value: deployBucket.bucketName });
  }
}
