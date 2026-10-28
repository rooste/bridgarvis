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
  alexaHost: process.env.BRIDGARVIS_ALEXA_HOST || undefined,
  alexaSmartHomeSkillId: process.env.BRIDGARVIS_ALEXA_SMARTHOME_SKILL_ID || undefined,
  siteAliases: (process.env.BRIDGARVIS_SITE_ALIASES ?? '').split(',').map((s) => s.trim()).filter(Boolean),
  mailTestDomain: required('BRIDGARVIS_MAIL_TEST_DOMAIN'),
  mailUiHost: required('BRIDGARVIS_MAIL_UI_HOST'),
  mailFromAddress: required('BRIDGARVIS_MAIL_FROM_ADDRESS'),
  responderAddresses: (process.env.BRIDGARVIS_RESPONDER_ADDRESSES ?? '').split(',').map((s) => s.trim()).filter(Boolean),
});
cdk.Tags.of(app).add('Project', 'bridgarvis');
