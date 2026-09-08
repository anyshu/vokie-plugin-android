import { spawn } from 'node:child_process';

// TXT keys must match the parsers in the Android app (VokieDevice.java) and the
// Harmony client (WifiDiscovery.ets): a service is only accepted when it
// advertises v=2, auth=sas-p256-v2, a non-empty instance id, and usable ipv4
// addresses. `name` is the human-readable PC name; `ipv4` may list several
// comma-separated LAN addresses and is omitted when the host has none.
export function advertise(port, instanceId, { name, ipv4 = [] }, onError) {
  const txt = [
    'v=2',
    'auth=sas-p256-v2',
    `instance=${instanceId}`,
    `name=${name}`,
    ...(ipv4.length > 0 ? [`ipv4=${ipv4.join(',')}`] : [])
  ];
  const args = ['-R', `Vokie ${name}`, '_vokie-phone._tcp', 'local', String(port), ...txt];
  const child = spawn(process.env.VOKIE_PHONE_DNS_SD || 'dns-sd', args, { stdio: ['ignore', 'ignore', 'pipe'] });
  child.on('error', (error) => onError?.(`mDNS unavailable: ${error.message}`));
  child.stderr?.on('data', (data) => onError?.(String(data).trim()));
  return () => child.kill();
}
