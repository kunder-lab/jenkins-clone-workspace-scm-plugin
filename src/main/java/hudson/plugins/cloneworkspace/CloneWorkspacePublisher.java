/*
 * The MIT License
 * 
 * Copyright (c) 2004-2010, Andrew Bayer
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.cloneworkspace;

import hudson.WorkspaceSnapshot;
import hudson.Util;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Extension;

import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.DirScanner;
import hudson.util.FormValidation;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;

import net.sf.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.logging.Logger;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;


/**
 * {@link Recorder} that archives a build's workspace (or subset thereof) as a {@link WorkspaceSnapshot},
 * for use by another project using {@link CloneWorkspaceSCM}.
 *
 * @author Andrew Bayer
 */
public class CloneWorkspacePublisher extends Recorder implements SimpleBuildStep {
    /**
     * The glob we'll archive.
     */
    private final String workspaceGlob;

    /**
     * The glob we'll exclude from the archive.
     */
    private final String workspaceExcludeGlob;

    /**
     * The criteria which determines whether we'll archive a given build's workspace.
     * Can be "Any" (meaning most recent completed build), "Not Failed" (meaning most recent unstable/stable build),
     * or "Successful" (meaning most recent stable build).
     */
    private final String criteria;

    /**
     * The method by which the SCM will be archived.
     * Can by "TAR" or "ZIP".
     */
    private final String archiveMethod;

    /**
     * If true, don't use the Ant default file glob excludes.
     */
    private final boolean overrideDefaultExcludes;

    @DataBoundConstructor
    public CloneWorkspacePublisher(String workspaceGlob, String workspaceExcludeGlob, String criteria, String archiveMethod, boolean overrideDefaultExcludes) {
        this.workspaceGlob = workspaceGlob.trim();
        this.workspaceExcludeGlob = Util.fixEmptyAndTrim(workspaceExcludeGlob);
        this.criteria = criteria;
        this.archiveMethod = archiveMethod;
        this.overrideDefaultExcludes = overrideDefaultExcludes;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public String getWorkspaceGlob() {
        return workspaceGlob;
    }

    public String getWorkspaceExcludeGlob() {
        return workspaceExcludeGlob;
    }

    public String getCriteria() {
        return criteria;
    }

    public String getArchiveMethod() {
        return archiveMethod;
    }

    public boolean getOverrideDefaultExcludes() {
        return overrideDefaultExcludes;
    }

    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws InterruptedException {
        
        String realIncludeGlob;
        // Default to **/* if no glob is specified.
        if (workspaceGlob.length()==0) {
            realIncludeGlob = "**/*";
        }
        else {
            try {
                realIncludeGlob = build.getEnvironment(listener).expand(workspaceGlob);
            } catch (IOException e) {
                // We couldn't get an environment for some reason, so we'll just use the original.
                realIncludeGlob = workspaceGlob;
            }
        }
        
        String realExcludeGlob = null;
        // Default to empty if no glob is specified.
        if (Util.fixNull(workspaceExcludeGlob).length()!=0) {
            try {
                realExcludeGlob = build.getEnvironment(listener).expand(workspaceExcludeGlob);
            } catch (IOException e) {
                // We couldn't get an environment for some reason, so we'll just use the original.
                realExcludeGlob = workspaceExcludeGlob;
            }
        }
    
        
        
        Result criteriaResult = CloneWorkspaceUtil.getResultForCriteria(criteria);
        
        return doPerform(build.getResult().isBetterOrEqualTo(criteriaResult), build.getWorkspace(), realIncludeGlob, realExcludeGlob, (TaskListener)listener, (Run)build, launcher);
        
    }        

    @Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException {        
        String realIncludeGlob;
        // Default to **/* if no glob is specified.
        if (workspaceGlob.length() == 0) {
            realIncludeGlob = "**/*";
        } else {
            try {
                realIncludeGlob = build.getEnvironment(listener).expand(workspaceGlob);
            } catch (IOException e) {
                // We couldn't get an environment for some reason, so we'll just use the original.
                realIncludeGlob = workspaceGlob;
            }
        }
        
        String realExcludeGlob = null;
        // Default to empty if no glob is specified.
        if (Util.fixNull(workspaceExcludeGlob).length()!=0) {
            try {
                realExcludeGlob = build.getEnvironment(listener).expand(workspaceExcludeGlob);
            } catch (IOException e) {
                // We couldn't get an environment for some reason, so we'll just use the original.
                realExcludeGlob = workspaceExcludeGlob;
            }
        }
        
        Result criteriaResult = CloneWorkspaceUtil.getResultForCriteria(criteria);
        
        boolean betterOrEqualToCriteria = true;
        if(build.getResult() != null) {
            betterOrEqualToCriteria = build.getResult().isBetterOrEqualTo(criteriaResult);
        }
        
        doPerform(betterOrEqualToCriteria, workspace, realIncludeGlob, realExcludeGlob, listener, build, launcher); 

    }

    public WorkspaceSnapshot snapshot(Run<?,?> build, FilePath ws, String includeGlob, String excludeGlob, Boolean overwriteDefaultExcludes, Launcher launcher, TaskListener listener, String archiveMethod) throws IOException, InterruptedException {
        DirScanner scanner = new DirScanner.Glob(includeGlob, excludeGlob, !overrideDefaultExcludes);
        File wss = new File(build.getRootDir(), CloneWorkspaceUtil.getFileNameForMethod(archiveMethod));
        switch (archiveMethod) {
            case "ZIP":
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(wss))) {
                    ws.zip(os, scanner);
                }
                
                return new WorkspaceSnapshotZip();
            case "TARONLY":
            {
                OutputStream os = new BufferedOutputStream(FilePath.TarCompression.NONE.compress(new FileOutputStream(wss)));
                try {
                    ws.tar(os, scanner);
                } finally {
                    os.close();
                }
                
                return new WorkspaceSnapshotTarOnly();
            }
            case "TAR-NATIVE":
                NativeTarUtil.archive(ws, wss, includeGlob, excludeGlob, launcher, listener);
                
                return new WorkspaceSnapshotTarNative();
            default:
            {
                try (OutputStream os = new BufferedOutputStream(FilePath.TarCompression.GZIP.compress(new FileOutputStream(wss)))) {
                    ws.tar(os, scanner);
                }
                
                return new WorkspaceSnapshotTar();
            }
        }
    }
    
    private boolean doPerform(boolean betterOrEqualToCriteria, FilePath ws, String realIncludeGlob, String realExcludeGlob, TaskListener listener, Run build, Launcher launcher) {
        Result criteriaResult = CloneWorkspaceUtil.getResultForCriteria(criteria);

        if (!betterOrEqualToCriteria) {
            listener.getLogger().println(Messages.CloneWorkspacePublisher_CriteriaNotMet(criteriaResult));
            return true;
        }
        else {
            listener.getLogger().println(Messages.CloneWorkspacePublisher_ArchivingWorkspace());
            if (ws==null) { // #3330: slave down?
                return true;
            }
            
            try {
                
                String includeMsg = ws.validateAntFileMask(realIncludeGlob);
                String excludeMsg = null;
                if (realExcludeGlob != null) {
                    ws.validateAntFileMask(realExcludeGlob);
                }
                // This means we found something.
                if ((includeMsg == null) && (excludeMsg == null)) {

                    build.addAction(snapshot(build, ws, realIncludeGlob, realExcludeGlob, !overrideDefaultExcludes, launcher, listener, archiveMethod));

                    // Find the next most recent build meeting this criteria with an archived snapshot.
                    Run<?,?> previousArchivedBuild = CloneWorkspaceUtil.getMostRecentRunForCriteriaWithSnapshot(build.getPreviousBuild(), criteria);
                    
                    if (previousArchivedBuild!=null) {
                        listener.getLogger().println(Messages.CloneWorkspacePublisher_DeletingOld(previousArchivedBuild.getDisplayName()));
                        
                        try {
                            File oldWss = new File(previousArchivedBuild.getRootDir(), CloneWorkspaceUtil.getFileNameForMethod(archiveMethod));
                            Util.deleteFile(oldWss);
                        } catch (IOException e) {
                           e.printStackTrace(listener.error(e.getMessage()));
                        }
                    }

                    return true;
                } else {
                    listener.getLogger().println(Messages.CloneWorkspacePublisher_NoMatchFound(realIncludeGlob,includeMsg));
                    return true;
                }
            } catch (IOException e) {
                Util.displayIOException(e,listener);
                e.printStackTrace(listener.error(
                        Messages.CloneWorkspacePublisher_FailedToArchive(realIncludeGlob)));
                return true;
            } catch (InterruptedException e) {
                e.printStackTrace(listener.error(
                        Messages.CloneWorkspacePublisher_FailedToArchive(realIncludeGlob)));
                return true;
            }
        }
    }
    
    public static final class WorkspaceSnapshotTar extends WorkspaceSnapshot {
        @Override
        public void restoreTo(AbstractBuild<?,?> owner, FilePath dst, TaskListener listener) throws IOException, InterruptedException {
            File wss = new File(owner.getRootDir(), CloneWorkspaceUtil.getFileNameForMethod("TAR"));
            new FilePath(wss).untar(dst, FilePath.TarCompression.GZIP);
        }
    }

    public static final class WorkspaceSnapshotTarOnly extends WorkspaceSnapshot {
        @Override
        public void restoreTo(AbstractBuild<?,?> owner, FilePath dst, TaskListener listener) throws IOException, InterruptedException {
            File wss = new File(owner.getRootDir(), CloneWorkspaceUtil.getFileNameForMethod("TARONLY"));
            new FilePath(wss).untar(dst, FilePath.TarCompression.NONE);
        }
    }

    public static final class WorkspaceSnapshotTarNative extends WorkspaceSnapshot {
        @Override
        public void restoreTo(AbstractBuild<?, ?> owner, FilePath dst, TaskListener listener) throws IOException, InterruptedException {
        }

        public void restoreTo(AbstractBuild<?, ?> owner, FilePath dst, TaskListener listener, Launcher launcher) throws IOException, InterruptedException {
            File wss = new File(owner.getRootDir(), CloneWorkspaceUtil.getFileNameForMethod("TAR-NATIVE"));
            NativeTarUtil.unarchive(wss, dst, launcher, listener);
        }
    }

    public static final class WorkspaceSnapshotZip extends WorkspaceSnapshot {
        @Override
        public void restoreTo(AbstractBuild<?,?> owner, FilePath dst, TaskListener listener) throws IOException, InterruptedException {
            File wss = new File(owner.getRootDir(), CloneWorkspaceUtil.getFileNameForMethod("ZIP"));
            new FilePath(wss).unzip(dst);
        }
    }

    @Symbol("CloneWorkspace")
    @Extension public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        public DescriptorImpl() {
            super(CloneWorkspacePublisher.class);
        }

        @Override
        public String getDisplayName() {
            return Messages.CloneWorkspacePublisher_DisplayName();
        }

        /**
         * Performs on-the-fly validation on the file mask wildcard.
         * @param project project
         * @param value value
         * @return value
         * @throws java.io.IOException Exception
         */
        public FormValidation doCheckWorkspaceGlob(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException {
            if(project != null) {
                return FilePath.validateFileMask(project.getSomeWorkspace(), value);
            } else {
                return null;
            }
        }

        @Override
        public CloneWorkspacePublisher newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return req.bindJSON(CloneWorkspacePublisher.class,formData);
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }

    private static final Logger LOGGER = Logger.getLogger(CloneWorkspacePublisher.class.getName());

}
