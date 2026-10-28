import * as fs from 'node:fs';
import * as path from 'node:path';
import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as smsvoice from 'aws-cdk-lib/aws-smsvoice';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as nodejs from 'aws-cdk-lib/aws-lambda-nodejs';
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
  /** Name Alexa calls (nginx forwards only POST /alexa to the gateway). */
  alexaHost?: string;
  /** Smart home skill ("Juujarvis Sensors") allowed to invoke the sensor Lambda. */
  alexaSmartHomeSkillId?: string;
  /** More names serving the same website (e.g. the bare domain and www). */
  siteAliases?: string[];
  /** Subdomain whose mail is received through SES while the main domain's MX still points elsewhere. */
  mailTestDomain: string;
  /** Mail UI name; nginx passes it through to the gateway (browser client certificates). */
  mailUiHost: string;
  /** The only address the gateway may send email from (e.g. the assistant's address). */
  mailFromAddress: string;
  /** Test mailboxes the auto-responder answers from (may send as these too); "prefix*@domain" allowed. */
  responderAddresses?: string[];
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
    if (props.alexaHost) {
      new route53.ARecord(this, 'AlexaRecord', {
        zone,
        recordName: props.alexaHost,
        target: route53.RecordTarget.fromIpAddresses(eip.ref),
        ttl: cdk.Duration.minutes(5),
      });
    }
    (props.siteAliases ?? []).forEach((name, i) => {
      new route53.ARecord(this, `SiteAlias${i}`, {
        zone,
        recordName: name,
        target: route53.RecordTarget.fromIpAddresses(eip.ref),
        ttl: cdk.Duration.minutes(5),
      });
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
    // Domain policies: only SES may send for the apex. DMARC quarantines unaligned mail (all our mail is
    // DKIM-signed by SES; aggregate reports, delivered to our own mailbox, showed no failures).
    new route53.TxtRecord(this, 'ApexSpf', {
      zone, values: ['v=spf1 include:amazonses.com ~all'], ttl: cdk.Duration.hours(1),
    });
    new route53.TxtRecord(this, 'Dmarc', {
      zone, recordName: `_dmarc.${props.zoneName}`,
      values: [`v=DMARC1; p=quarantine; rua=mailto:dmarc-reports@${props.zoneName}; fo=1`],
      ttl: cdk.Duration.hours(1),
    });
    // BIMI without a certificate (a= empty): the logo shows only where a mark certificate isn't required
    new route53.TxtRecord(this, 'Bimi', {
      zone, recordName: `default._bimi.${props.zoneName}`,
      values: [`v=BIMI1; l=https://${props.siteHost}/bimi.svg; a=;`],
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

    // ---------------------------------------------------------------- SMS (inbound)

    // The toll-free number's two-way messaging destination is this topic. The number itself (and its
    // registration) lives outside CDK; point it here once with
    //   aws pinpoint-sms-voice-v2 update-phone-number --phone-number-id <id> --two-way-enabled \
    //     --two-way-channel-arn <SmsInboundTopicArn>
    const smsDlq = new sqs.Queue(this, 'SmsInboundDlq', { retentionPeriod: cdk.Duration.days(14), enforceSSL: true });
    const smsQueue = new sqs.Queue(this, 'SmsInboundQueue', {
      retentionPeriod: cdk.Duration.days(14),
      visibilityTimeout: cdk.Duration.minutes(2),
      deadLetterQueue: { queue: smsDlq, maxReceiveCount: 5 },
      enforceSSL: true,
    });
    const smsInbound = new sns.Topic(this, 'SmsInbound');
    smsInbound.addSubscription(new snsSubs.SqsSubscription(smsQueue, { rawMessageDelivery: true }));
    smsInbound.addToResourcePolicy(new iam.PolicyStatement({
      principals: [new iam.ServicePrincipal('sms-voice.amazonaws.com')],
      actions: ['sns:Publish'],
      resources: [smsInbound.topicArn],
      conditions: { StringEquals: { 'aws:SourceAccount': this.account } },
    }));

    // ---------------------------------------------------------------- SMS (sending, consent, delivery events)

    // Who opted in (JOIN) or out (STOP / AWS opt-out list); only opted-in numbers are texted.
    const smsConsent = new dynamodb.TableV2(this, 'SmsConsent', {
      partitionKey: { name: 'number', type: dynamodb.AttributeType.STRING },
      billing: dynamodb.Billing.onDemand(),
      pointInTimeRecovery: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
    const smsEventsDlq = new sqs.Queue(this, 'SmsEventsDlq', { retentionPeriod: cdk.Duration.days(14), enforceSSL: true });
    const smsEventsQueue = new sqs.Queue(this, 'SmsEventsQueue', {
      retentionPeriod: cdk.Duration.days(14),
      visibilityTimeout: cdk.Duration.minutes(2),
      deadLetterQueue: { queue: smsEventsDlq, maxReceiveCount: 5 },
      enforceSSL: true,
    });
    const smsEvents = new sns.Topic(this, 'SmsEvents');
    smsEvents.addSubscription(new snsSubs.SqsSubscription(smsEventsQueue, { rawMessageDelivery: true }));
    const smsEventsPolicy = smsEvents.addToResourcePolicy(new iam.PolicyStatement({
      principals: [new iam.ServicePrincipal('sms-voice.amazonaws.com')],
      actions: ['sns:Publish'],
      resources: [smsEvents.topicArn],
      conditions: { StringEquals: { 'aws:SourceAccount': this.account } },
    }));
    const smsConfig = new smsvoice.CfnConfigurationSet(this, 'SmsConfig', {
      configurationSetName: 'bridgarvis-sms',
      eventDestinations: [{
        eventDestinationName: 'to-sns',
        enabled: true,
        matchingEventTypes: ['TEXT_ALL'],
        snsDestination: { topicArn: smsEvents.topicArn },
      }],
    });
    // The topic policy must exist before AWS validates the destination
    if (smsEventsPolicy.policyDependable) smsConfig.node.addDependency(smsEventsPolicy.policyDependable);

    // Gateway access
    messages.grantReadWriteData(role);
    mailBucket.grantRead(role, 'inbound/*');
    mailBucket.grantDelete(role, 'inbound/*');
    mailBucket.grantReadWrite(role, 'parsed/*');
    parseQueue.grantConsumeMessages(role);
    sendEventsQueue.grantConsumeMessages(role);
    smsQueue.grantConsumeMessages(role);
    smsEventsQueue.grantConsumeMessages(role);
    smsConsent.grantReadWriteData(role);
    // Send from the account's toll-free number with the configuration set; read the opt-out list
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['sms-voice:SendTextMessage'],
      resources: [
        `arn:aws:sms-voice:${this.region}:${this.account}:phone-number/*`,
        `arn:aws:sms-voice:${this.region}:${this.account}:configuration-set/${smsConfig.configurationSetName}`,
        `arn:aws:sms-voice:${this.region}:${this.account}:opt-out-list/*`,
      ],
    }));
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['sms-voice:DescribeOptedOutNumbers'],
      resources: [`arn:aws:sms-voice:${this.region}:${this.account}:opt-out-list/*`],
    }));
    // Send only from the assistant address (and the auto-responder test mailboxes), only with our configuration set (so every send produces events).
    // identity/* because in the SES sandbox the recipients' verified identities are checked too; the
    // FromAddress condition is what limits the sender.
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['ses:SendEmail', 'ses:SendRawEmail'],
      resources: [
        `arn:aws:ses:${this.region}:${this.account}:identity/*`,
        `arn:aws:ses:${this.region}:${this.account}:configuration-set/${configurationSet.configurationSetName}`,
      ],
      // StringLike: responder mailboxes may be local-part prefixes ("customer*@domain")
      conditions: { StringLike: { 'ses:FromAddress': [props.mailFromAddress, ...(props.responderAddresses ?? [])] } },
    }));

    // ---------------------------------------------------------------- Alexa sensors (smart home skill)

    // One virtual contact sensor per Echo; Bridgarvis opens it when a late answer waits for that Echo,
    // and a household routine then opens the Juujarvis skill there. Items: sensor#<endpointId>, token.
    const alexaSensors = new dynamodb.TableV2(this, 'AlexaSensors', {
      partitionKey: { name: 'pk', type: dynamodb.AttributeType.STRING },
      billing: dynamodb.Billing.onDemand(),
    });
    const smartHomeParams = '/bridgarvis/alexa-smarthome';
    const smartHomeParamArn = `arn:aws:ssm:${this.region}:${this.account}:parameter${smartHomeParams}/*`;
    const smartHome = new nodejs.NodejsFunction(this, 'AlexaSmartHome', {
      entry: path.join(import.meta.dirname, '..', 'lambda', 'alexa-smarthome', 'index.ts'),
      runtime: lambda.Runtime.NODEJS_22_X,
      architecture: lambda.Architecture.ARM_64,
      memorySize: 256,
      timeout: cdk.Duration.seconds(6),
      environment: { SENSORS_TABLE: alexaSensors.tableName, PARAM_PREFIX: smartHomeParams },
      bundling: { externalModules: ['@aws-sdk/*'], minify: true },
      logGroup: new logs.LogGroup(this, 'AlexaSmartHomeLogs', {
        retention: logs.RetentionDays.ONE_MONTH,
        removalPolicy: cdk.RemovalPolicy.DESTROY,
      }),
    });
    alexaSensors.grantReadWriteData(smartHome);
    smartHome.addToRolePolicy(new iam.PolicyStatement({
      actions: ['ssm:GetParameters', 'ssm:GetParameter'],
      resources: [smartHomeParamArn],
    }));
    if (props.alexaSmartHomeSkillId) {
      smartHome.addPermission('AlexaSmartHomeSkill', {
        principal: new iam.ServicePrincipal('alexa-connectedhome.amazon.com'),
        eventSourceToken: props.alexaSmartHomeSkillId,
      });
    }
    // The host flips sensors and refreshes the event-gateway token
    alexaSensors.grantReadWriteData(role);
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['ssm:GetParameters', 'ssm:GetParameter'],
      resources: [smartHomeParamArn],
    }));
    new cdk.CfnOutput(this, 'AlexaSensorsTable', { value: alexaSensors.tableName });
    new cdk.CfnOutput(this, 'AlexaSmartHomeFunctionArn', { value: smartHome.functionArn });

    new cdk.CfnOutput(this, 'MessagesTable', { value: messages.tableName });
    new cdk.CfnOutput(this, 'MailBucketName', { value: mailBucket.bucketName });
    new cdk.CfnOutput(this, 'MailParseQueueUrl', { value: parseQueue.queueUrl });
    new cdk.CfnOutput(this, 'MailParseDlqUrl', { value: parseDlq.queueUrl });
    new cdk.CfnOutput(this, 'MailEventsQueueUrl', { value: sendEventsQueue.queueUrl });
    new cdk.CfnOutput(this, 'SmsInboundTopicArn', { value: smsInbound.topicArn });
    new cdk.CfnOutput(this, 'SmsInboundQueueUrl', { value: smsQueue.queueUrl });
    new cdk.CfnOutput(this, 'SmsEventsQueueUrl', { value: smsEventsQueue.queueUrl });
    new cdk.CfnOutput(this, 'SmsConsentTable', { value: smsConsent.tableName });
    new cdk.CfnOutput(this, 'SmsConfigurationSet', { value: smsConfig.configurationSetName! });
    new cdk.CfnOutput(this, 'MailConfigurationSet', { value: configurationSet.configurationSetName });

    new cdk.CfnOutput(this, 'InstanceId', { value: instance.instanceId });
    new cdk.CfnOutput(this, 'PublicIp', { value: eip.ref });
    new cdk.CfnOutput(this, 'DeployBucketName', { value: deployBucket.bucketName });
  }
}
