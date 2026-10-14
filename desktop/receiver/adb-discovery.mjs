import {execFile} from 'node:child_process';
import {existsSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url));
export const adbPath=[path.resolve(root,'../../.tools/android-sdk/platform-tools/adb.exe'),path.resolve(root,'runtime/adb.exe')].find(existsSync);
const adb=args=>new Promise((resolve,reject)=>{if(!adbPath)return reject(Error('ADB 不可用'));execFile(adbPath,args,{windowsHide:true,timeout:4500,maxBuffer:65536},(e,out)=>e?reject(Error('USB 连接未就绪')):resolve(out));});
export async function discoverPhone(){
  if(!adbPath)return null;
  const devices=(await adb(['devices'])).split(/\r?\n/).map(line=>line.match(/^([^\s]+)\s+device$/)?.[1]).filter(Boolean);if(devices.length!==1)return null;
  const serial=devices[0];
  const prefs=await adb(['-s',serial,'shell','run-as','com.hearlens.app','cat','shared_prefs/wifi_access.xml']);
  const key=prefs.match(/<string name="token">([a-f0-9]{32})<\/string>/i)?.[1];if(!key)throw Error('手机已通过 USB 连接，请先在手机打开听见视界并开启无线共享');
  const addr=await adb(['-s',serial,'shell','ip','-o','-4','addr','show']);
  const ips=addr.split(/\r?\n/).filter(l=>/\b(?:wlan\d+|ap\d+|swlan\d+)\b/.test(l)).map(l=>l.match(/inet\s+(\d+\.\d+\.\d+\.\d+)\//)?.[1]).filter(Boolean);
  if(!ips.length)throw Error('手机 USB 已连接，但尚无 Wi-Fi 或热点地址；请让手机和电脑连接同一局域网');
  return {url:`http://${ips[0]}:18766/#${key}`,serial};
}
