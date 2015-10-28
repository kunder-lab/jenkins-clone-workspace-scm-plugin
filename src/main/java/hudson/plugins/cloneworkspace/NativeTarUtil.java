package hudson.plugins.cloneworkspace;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.TaskListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;

public class NativeTarUtil {
    public static void archive(FilePath workspaceSource, File dest, String includeGlob, String excludeGlob, Launcher launcher, TaskListener listener) throws InterruptedException {
        try {
            FilePath tmp_file = new FilePath(workspaceSource, "tmp.tar");

            Launcher.ProcStarter procStarter = launcher.launch();

            LinkedList<String> cmds = new LinkedList<String>();

            LinkedList<String> excludes = new LinkedList<String>();
            excludes.add("--exclude=tmp.tar");
            if(excludeGlob != null && excludeGlob.length() > 0) {
                for (String s: excludeGlob.split(" ")) {
                    excludes.add("--exclude="+"\""+s+"\"");
                }
            }

            cmds.add("tar");
            if(excludes.size() > 0) {
                cmds.addAll(excludes);
            }
            cmds.add("-cvf");
            cmds.add("tmp.tar");
            if(includeGlob.equals("**/*")) {
                includeGlob = ".";
            }
            cmds.add(includeGlob);

            procStarter = procStarter.pwd(workspaceSource)
                    .cmds(cmds)
                    .stderr(listener.getLogger())
                    .stdout(listener.getLogger());
            Proc proc = launcher.launch(procStarter);
            if (proc.join() != 0) {
                throw new InterruptedException("Failed to archive the project");
            }
            tmp_file.copyTo(new FileOutputStream(dest));
            tmp_file.delete();
        } catch (IOException e) {
            throw new InterruptedException("Failed to archive the project");
        }
    }

    public static void unarchive(File source, FilePath workspaceDest, Launcher launcher, TaskListener listener) throws InterruptedException {

        try {
            FilePath dst_file = new FilePath(workspaceDest, "tmp.tar");
            (new FilePath(source)).copyTo(dst_file);

            Launcher.ProcStarter procStarter = launcher.launch();
            procStarter = procStarter.pwd(workspaceDest)
                    .cmds("tar", "-xf", "tmp.tar")
                    .stderr(listener.getLogger())
                    .stdout(listener.getLogger());
            Proc proc = launcher.launch(procStarter);
            if (proc.join() != 0) {
                throw new InterruptedException("Failed to archive the project");
            }

        } catch (IOException e) {
            throw new InterruptedException("Failed to archive the project");
        }
    }
}
