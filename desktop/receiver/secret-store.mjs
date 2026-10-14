import {execFile} from 'node:child_process';
import {readFile,writeFile,mkdir,rename} from 'node:fs/promises';
import path from 'node:path';

const assembly=`$ErrorActionPreference='Stop'; $null=[Reflection.Assembly]::LoadWithPartialName('System.Security'); `;
const encode=assembly+`$b=[Text.Encoding]::UTF8.GetBytes([Console]::In.ReadToEnd()); [Console]::Out.Write([Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)))`;
const decode=assembly+`$b=[Convert]::FromBase64String([Console]::In.ReadToEnd()); [Console]::Out.Write([Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)))`;
function crypt(script,value){return new Promise((resolve,reject)=>{const child=execFile('powershell.exe',['-NoLogo','-NoProfile','-NonInteractive','-Command',script],{windowsHide:true,timeout:12000,maxBuffer:30000},(err,out)=>err?reject(Error('本机加密配置暂不可用')):resolve(out.trim()));child.stdin.end(value);});}
export class SecretStore {
  constructor(directory){this.file=path.join(directory,'connection.json');}
  async load(){try{const saved=JSON.parse(await readFile(this.file,'utf8'));if(saved.format!==2)throw Error();return {url:saved.base+'/#'+await crypt(decode,saved.protectedKey)};}catch(e){if(e.code==='ENOENT')return null;throw Error('读取已保存连接失败，请重新粘贴手机链接');}}
  async save(connection){await mkdir(path.dirname(this.file),{recursive:true});const data={format:2,base:connection.base,protectedKey:await crypt(encode,connection.key)};const temp=this.file+'.tmp';await writeFile(temp,JSON.stringify(data),{mode:0o600});await rename(temp,this.file);}
}
