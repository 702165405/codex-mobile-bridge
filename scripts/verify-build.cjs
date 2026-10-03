'use strict';
const fs=require('node:fs'),path=require('node:path');
const {Arch}=require('builder-util');

module.exports=async context=>{
  const root=context.packager.projectDir;
  const metadata=JSON.parse(fs.readFileSync(path.join(root,'dist/update-version.json'),'utf8'));
  const arch=Arch[context.arch],platform=context.electronPlatformName;
  if(metadata.platform!==platform||metadata.arch!==arch||metadata.version!==context.packager.appInfo.version){
    throw Error(`Gateway does not match ${platform}/${arch}/${context.packager.appInfo.version}. Run scripts/build-desktop.py on the target OS and architecture first.`);
  }
  const executable=path.join(root,'dist/gateway',platform==='win32'?'codex-mobile-gateway.exe':'codex-mobile-gateway');
  fs.accessSync(executable,fs.constants.R_OK);
};
