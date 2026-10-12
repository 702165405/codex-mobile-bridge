import io.github.try2love.codexbridge.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public class UpdateInstallSessionTests {
 static int checks;
 static byte[] bytes="verified update fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
 static MobileRelease release;
 static File directory;
 public static void main(String[] args)throws Exception {
  directory=new File(args[0]);directory.mkdirs();StringBuilder digest=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))digest.append(String.format("%02x",b));
  release=new MobileRelease("2.0.3","",bytes.length,digest.toString());
  File orphan=File.createTempFile("bridge-download-",".part",directory);Files.write(orphan.toPath(),bytes);
  check(UpdateInstallSession.restore(directory,"2.0.2")==null&&!orphan.exists(),"process-death partial APK reclaimed without a record");
  UpdateInstallSession session=create();session.save();
  orphan=File.createTempFile("bridge-download-",".part",directory);Files.write(orphan.toPath(),bytes);
  check(UpdateInstallSession.restore(directory,"2.0.2")!=null&&!orphan.exists(),"unreferenced APK removed while recorded package preserved");
  check(session.completed(),"one automatic handoff");check(!session.completed(),"queued completion ignored");
  check(session.beginInstall(),"first installer request");check(!session.beginInstall(),"duplicate installer blocked");
  session.awaitPermission();check(!session.beginInstall(),"permission request not repeated");
  UpdateInstallSession restored=UpdateInstallSession.restore(directory,"2.0.2");
  check(restored!=null&&restored.waitingPermission(),"permission survives recreation");
  check(!restored.completed(),"restore does not replay completion");
  check(restored.permissionReturned(),"permission return consumed");check(!restored.permissionReturned(),"return consumed once");
  check(restored.beginInstall(),"resume after permission");restored.finishInstall();
  check(!restored.completed(),"cancelled installer not automatically reopened");
  check(restored.beginInstall(),"explicit retry allowed");restored.finishInstall();
  check(Arrays.equals(bytes,Files.readAllBytes(restored.task.result().file.toPath())),"saved APK remains readable");
  Thread.currentThread().interrupt();
  try{UpdateInstallSession.restore(directory,"2.0.2");throw new AssertionError("interrupted restore continued");}catch(InterruptedIOException expected){checks++;}finally{Thread.interrupted();}
  check(UpdateInstallSession.restore(directory,"2.0.2")!=null,"interrupted verification preserves recoverable APK");
  restored.cancel();check(UpdateInstallSession.restore(directory,"2.0.2")==null,"cancel clears recovery");
  check(directory.list().length==0,"cancel removes APK");
  session=create();session.save();check(UpdateInstallSession.restore(directory,"2.0.3")==null,"installed version clears pending package");check(directory.list().length==0,"successful upgrade cleanup");
  session=create();session.save();Files.write(session.task.result().file.toPath(),new byte[bytes.length]);
  try{UpdateInstallSession.restore(directory,"2.0.2");throw new AssertionError("tampered APK accepted");}catch(IOException expected){checks++;}
  try{session.task.verifyCompletedUpdate();throw new AssertionError("tampered install accepted");}catch(IOException expected){checks++;}session.cancel();
  session=create();session.save();Files.write(session.task.result().file.toPath(),new byte[]{1});
  try{UpdateInstallSession.restore(directory,"2.0.2");throw new AssertionError("truncated APK accepted");}catch(IOException expected){checks++;}session.cancel();
  session=create();session.save();File record=new File(directory,"pending.properties");Properties p=new Properties();try(InputStream in=new FileInputStream(record)){p.load(in);}p.setProperty("file","../other.apk");try(OutputStream out=new FileOutputStream(record)){p.store(out,null);}
  try{UpdateInstallSession.restore(directory,"2.0.2");throw new AssertionError("escaped path accepted");}catch(IOException expected){checks++;}session.cancel();
  check(directory.list().length==0,"fixtures cleaned");System.out.println(checks+" update install/recovery checks passed");
 }
 static UpdateInstallSession create()throws Exception {File apk=File.createTempFile("bridge-download-",".part",directory);Files.write(apk.toPath(),bytes);ArtifactDownload.Task task=ArtifactDownload.Task.restoreUpdate(release,directory,apk.getName());return new UpdateInstallSession(release,task,directory);}
 static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
}
