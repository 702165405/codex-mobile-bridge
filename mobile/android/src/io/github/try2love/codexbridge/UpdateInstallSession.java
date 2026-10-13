package io.github.try2love.codexbridge;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** One verified package and its installation handoff, independent of Activity recreation. */
public final class UpdateInstallSession {
 public final MobileRelease release;public final ArtifactDownload.Task task;
 private final File directory;
 private boolean completionHandled,opening,permissionPending,cancelled;
 public UpdateInstallSession(MobileRelease release,ArtifactDownload.Task task,File directory){this.release=release;this.task=task;this.directory=directory;}
 public synchronized boolean completed(){if(cancelled||completionHandled||task.state!=ArtifactDownload.State.COMPLETE)return false;completionHandled=true;return true;}
 public synchronized boolean beginInstall(){if(cancelled||opening||permissionPending||task.state!=ArtifactDownload.State.COMPLETE)return false;opening=true;return true;}
 public synchronized void finishInstall(){opening=false;}
 public synchronized boolean waitingPermission(){return permissionPending;}
 public synchronized void awaitPermission()throws IOException {permissionPending=true;opening=false;try{save();}catch(IOException e){permissionPending=false;throw e;}}
 public synchronized boolean permissionReturned()throws IOException {if(!permissionPending)return false;permissionPending=false;save();return true;}
 public synchronized void save()throws IOException {
  if(cancelled||task.state!=ArtifactDownload.State.COMPLETE)throw new IOException("Update not ready");
  ArtifactDownload.Result result=task.result();
  if(!directory.getCanonicalFile().equals(result.file.getCanonicalFile().getParentFile()))throw new IOException("Invalid update directory");
  Properties p=new Properties();p.setProperty("version",release.version);p.setProperty("size",Long.toString(release.size));p.setProperty("sha256",release.sha256);p.setProperty("file",result.file.getName());p.setProperty("permission",Boolean.toString(permissionPending));
  File staging=new File(directory,"pending.properties.tmp");
  try(FileOutputStream out=new FileOutputStream(staging)){p.store(out,null);out.getFD().sync();}
  Files.move(staging.toPath(),new File(directory,"pending.properties").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
 }
 public synchronized void cancel(){cancelled=true;permissionPending=false;opening=false;new File(directory,"pending.properties").delete();new File(directory,"pending.properties.tmp").delete();task.cancel();}
 public static UpdateInstallSession restore(File directory,String currentVersion)throws IOException {
  File record=new File(directory,"pending.properties");if(!record.isFile()){discard(directory);return null;}
  Properties p=new Properties();try(InputStream in=new FileInputStream(record)){p.load(in);}
  try{
   MobileRelease release=new MobileRelease(p.getProperty("version"),"",Long.parseLong(p.getProperty("size")),p.getProperty("sha256"));
   if(MobileRelease.compare(release.version,currentVersion)<=0){discard(directory);return null;}
   ArtifactDownload.Task task=ArtifactDownload.Task.restoreUpdate(release,directory,p.getProperty("file"));
   File[] orphans=directory.listFiles();if(orphans!=null)for(File file:orphans)if(file.getName().matches("bridge-download-[a-zA-Z0-9-]+\\.part")&&!file.getName().equals(p.getProperty("file")))file.delete();
   UpdateInstallSession session=new UpdateInstallSession(release,task,directory);session.completionHandled=true;session.permissionPending=Boolean.parseBoolean(p.getProperty("permission"));return session;
  }catch(RuntimeException e){throw new IOException("Invalid pending update",e);}
 }
 /** Only this private update directory is eligible for cleanup. */
 public static void discard(File directory){File[] files=directory.listFiles();if(files!=null)for(File file:files)if(file.getName().matches("bridge-download-[a-zA-Z0-9-]+\\.part|pending\\.properties(?:\\.tmp)?"))file.delete();}
}
