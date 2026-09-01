import { spawn } from 'node:child_process';

export function advertise(port, instanceId, name, onError) {
  const args = ['-R', name, '_vokie-phone._tcp', 'local', String(port), `v=1`, 'device=vokie-phone', `device_id=${instanceId}`, `name=${name}`, 'proto=2'];
  const child = spawn(process.env.VOKIE_PHONE_DNS_SD || 'dns-sd', args, { stdio: ['ignore', 'ignore', 'pipe'] });
  child.on('error', (error) => onError?.(`mDNS unavailable: ${error.message}`));
  child.stderr?.on('data', (data) => onError?.(String(data).trim()));
  return () => child.kill();
}
