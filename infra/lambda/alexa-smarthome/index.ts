/**
 * Smart home skill "Juujarvis Sensors": one virtual contact sensor per Echo. Bridgarvis "opens" a
 * sensor (via the Alexa event gateway) when a late answer waits for that Echo; the household's routine
 * "when <sensor> opens → open the Juujarvis skill on that Echo" then speaks it.
 *
 * Alexa calls this function for:
 *   Alexa.Discovery.Discover        → the sensors (from the sensors table, written by Bridgarvis)
 *   Alexa.ReportState               → a sensor's current state
 *   Alexa.Authorization.AcceptGrant → exchange the grant code for event-gateway tokens (stored encrypted)
 *
 * Only the smart home skill can invoke it (Lambda permission with the skill id). No secrets or tokens
 * are logged.
 */
import { DynamoDBClient, GetItemCommand, PutItemCommand, ScanCommand } from '@aws-sdk/client-dynamodb';
import { GetParametersCommand, SSMClient } from '@aws-sdk/client-ssm';
import { randomUUID } from 'node:crypto';

const db = new DynamoDBClient({});
const ssm = new SSMClient({});
const TABLE = process.env.SENSORS_TABLE!;
const PARAMS = process.env.PARAM_PREFIX!; // e.g. /bridgarvis/alexa-smarthome

type Json = Record<string, any>;

export const handler = async (event: Json): Promise<Json> => {
  const directive = event?.directive ?? {};
  const { namespace, name } = directive.header ?? {};
  console.log(`directive ${namespace}.${name}`);
  try {
    if (namespace === 'Alexa.Discovery' && name === 'Discover') return await discover();
    if (namespace === 'Alexa' && name === 'ReportState') return await reportState(directive);
    if (namespace === 'Alexa.Authorization' && name === 'AcceptGrant') return await acceptGrant(directive);
    return errorResponse(directive, 'INVALID_DIRECTIVE', `Unsupported directive ${namespace}.${name}`);
  } catch (e) {
    console.error(`directive ${namespace}.${name} failed: ${(e as Error).message}`);
    if (namespace === 'Alexa.Authorization') {
      return {
        event: {
          header: header('Alexa.Authorization', 'ErrorResponse'),
          payload: { type: 'ACCEPT_GRANT_FAILED', message: 'Could not store the authorization' },
        },
      };
    }
    return errorResponse(directive, 'INTERNAL_ERROR', 'Internal error');
  }
};

function header(namespace: string, name: string, correlationToken?: string): Json {
  return { namespace, name, payloadVersion: '3', messageId: randomUUID(), ...(correlationToken ? { correlationToken } : {}) };
}

/** The sensor as Alexa sees it; Bridgarvis builds the same structure for AddOrUpdateReport. */
function endpoint(endpointId: string, room: string): Json {
  return {
    endpointId,
    manufacturerName: 'Juujarvis',
    friendlyName: `Juujarvis ${room}`,
    description: `Opens when Juujarvis has an answer waiting for the ${room} Echo`,
    displayCategories: ['CONTACT_SENSOR'],
    capabilities: [
      {
        type: 'AlexaInterface', interface: 'Alexa.ContactSensor', version: '3',
        properties: { supported: [{ name: 'detectionState' }], proactivelyReported: true, retrievable: true },
      },
      {
        type: 'AlexaInterface', interface: 'Alexa.EndpointHealth', version: '3',
        properties: { supported: [{ name: 'connectivity' }], proactivelyReported: true, retrievable: true },
      },
      { type: 'AlexaInterface', interface: 'Alexa', version: '3' },
    ],
  };
}

async function discover(): Promise<Json> {
  const result = await db.send(new ScanCommand({
    TableName: TABLE,
    FilterExpression: 'begins_with(pk, :p)',
    ExpressionAttributeValues: { ':p': { S: 'sensor#' } },
  }));
  const endpoints = (result.Items ?? [])
    .filter((i) => i.endpointId?.S && i.room?.S)
    .map((i) => endpoint(i.endpointId.S!, i.room.S!));
  return { event: { header: header('Alexa.Discovery', 'Discover.Response'), payload: { endpoints } } };
}

async function reportState(directive: Json): Promise<Json> {
  const endpointId: string = directive.endpoint?.endpointId ?? '';
  const item = await db.send(new GetItemCommand({ TableName: TABLE, Key: { pk: { S: `sensor#${endpointId}` } } }));
  if (!item.Item) return errorResponse(directive, 'NO_SUCH_ENDPOINT', 'Unknown sensor');
  const now = new Date().toISOString();
  return {
    event: {
      header: header('Alexa', 'StateReport', directive.header?.correlationToken),
      endpoint: { endpointId },
      payload: {},
    },
    context: {
      properties: [
        { namespace: 'Alexa.ContactSensor', name: 'detectionState', value: item.Item.state?.S ?? 'NOT_DETECTED', timeOfSample: now, uncertaintyInMilliseconds: 0 },
        { namespace: 'Alexa.EndpointHealth', name: 'connectivity', value: { value: 'OK' }, timeOfSample: now, uncertaintyInMilliseconds: 0 },
      ],
    },
  };
}

/** Account linking with "Send Alexa Events": exchange the grant for tokens to call the event gateway. */
async function acceptGrant(directive: Json): Promise<Json> {
  const code: string | undefined = directive.payload?.grant?.code;
  if (!code) throw new Error('no grant code');
  const params = await ssm.send(new GetParametersCommand({
    Names: [`${PARAMS}/client-id`, `${PARAMS}/client-secret`],
    WithDecryption: true,
  }));
  const value = (suffix: string) => params.Parameters?.find((p) => p.Name === `${PARAMS}/${suffix}`)?.Value;
  const clientId = value('client-id');
  const clientSecret = value('client-secret');
  if (!clientId || !clientSecret) throw new Error('client credentials missing in Parameter Store');

  const response = await fetch('https://api.amazon.com/auth/o2/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'authorization_code', code, client_id: clientId, client_secret: clientSecret }),
  });
  if (!response.ok) throw new Error(`token exchange HTTP ${response.status}`);
  const tokens = (await response.json()) as Json;
  if (!tokens.refresh_token || !tokens.access_token) throw new Error('token exchange returned no tokens');

  await db.send(new PutItemCommand({
    TableName: TABLE,
    Item: {
      pk: { S: 'token' },
      refreshToken: { S: tokens.refresh_token },
      accessToken: { S: tokens.access_token },
      accessExpiresAt: { N: String(Math.floor(Date.now() / 1000) + Number(tokens.expires_in ?? 3600) - 60) },
      updatedAt: { S: new Date().toISOString() },
    },
  }));
  console.log('event gateway authorization stored');
  return { event: { header: header('Alexa.Authorization', 'AcceptGrant.Response'), payload: {} } };
}

function errorResponse(directive: Json, type: string, message: string): Json {
  return {
    event: {
      header: header('Alexa', 'ErrorResponse', directive.header?.correlationToken),
      ...(directive.endpoint?.endpointId ? { endpoint: { endpointId: directive.endpoint.endpointId } } : {}),
      payload: { type, message },
    },
  };
}
