#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { BridgarvisStack } from '../lib/bridgarvis-stack.js';

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Missing env var ${name} (see local.env.example)`);
  return value;
}

const app = new cdk.App();
new BridgarvisStack(app, 'BridgarvisStack', {
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION ?? 'us-east-1' },
  zoneName: required('BRIDGARVIS_ZONE'),
  hostName: required('BRIDGARVIS_HOST'),
  siteHost: required('BRIDGARVIS_SITE_HOST'),
  mailTestDomain: required('BRIDGARVIS_MAIL_TEST_DOMAIN'),
  mailUiHost: required('BRIDGARVIS_MAIL_UI_HOST'),
});
cdk.Tags.of(app).add('Project', 'bridgarvis');
