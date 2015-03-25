package org.jenkinsci.plugins.ivytrigger;

import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.console.AnnotatedLargeText;
import hudson.model.Action;
import hudson.model.AbstractProject;
import hudson.model.Node;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.jelly.XMLOutput;
import org.jenkinsci.lib.envinject.EnvInjectException;
import org.jenkinsci.lib.envinject.service.EnvVarsResolver;
import org.jenkinsci.lib.xtrigger.XTriggerDescriptor;
import org.jenkinsci.lib.xtrigger.XTriggerException;
import org.jenkinsci.lib.xtrigger.XTriggerLog;
import org.jenkinsci.plugins.ivytrigger.util.FilePathFactory;
import org.jenkinsci.plugins.ivytrigger.util.PropertiesFileContentExtractor;
import org.kohsuke.stapler.DataBoundConstructor;

import antlr.ANTLRException;


/**
 * @author Gregory Boissinot
 */
public class IvyTrigger extends AbstractIvyTriggerByFullContext<IvyTriggerContext> implements Serializable {

    private String ivyPath;

    private String ivySettingsPath;

    private String propertiesFilePath;

    private String propertiesContent;
    
    private boolean contextSerialized;

    private boolean debug;

    private boolean labelRestriction;

    private boolean enableConcurrentBuild;

    private transient FilePathFactory filePathFactory;

    private transient PropertiesFileContentExtractor propertiesFileContentExtractor;

    @DataBoundConstructor
    public IvyTrigger(String cronTabSpec, String ivyPath, String ivySettingsPath, String propertiesFilePath, String propertiesContent, LabelRestrictionClass labelRestriction, boolean enableConcurrentBuild, boolean contextSerialized, boolean debug) throws ANTLRException {
        super(cronTabSpec, (labelRestriction == null) ? null : labelRestriction.getTriggerLabel(), enableConcurrentBuild);
        this.ivyPath = Util.fixEmpty(ivyPath);
        this.ivySettingsPath = Util.fixEmpty(ivySettingsPath);
        this.propertiesFilePath = Util.fixEmpty(propertiesFilePath);
        this.propertiesContent = Util.fixEmpty(propertiesContent);
        this.contextSerialized = contextSerialized;
        this.debug = debug;
        this.labelRestriction = (labelRestriction == null) ? false : true;
        this.enableConcurrentBuild = enableConcurrentBuild;
    }

    @SuppressWarnings("unused")
    public String getIvyPath() {
        return ivyPath;
    }

    @SuppressWarnings("unused")
    public String getIvySettingsPath() {
        return ivySettingsPath;
    }

    @SuppressWarnings("unused")
    public String getPropertiesFilePath() {
        return propertiesFilePath;
    }

    @SuppressWarnings("unused")
    public String getPropertiesContent() {
        return propertiesContent;
    }


    @SuppressWarnings("unused")
    public boolean isContextSerialized() {
        return contextSerialized;
    }
    
    @SuppressWarnings("unused")
    public boolean isDebug() {
        return debug;
    }

    public boolean isLabelRestriction() {
        return labelRestriction;
    }

    public boolean isEnableConcurrentBuild() {
        return enableConcurrentBuild;
    }

    @Override
    public Collection<? extends Action> getProjectActions() {
        IvyTriggerAction action = new InternalIvyTriggerAction(this.getDescriptor().getDisplayName());
        return Collections.singleton(action);
    }


    public final class InternalIvyTriggerAction extends IvyTriggerAction {

        private transient String label;

        public InternalIvyTriggerAction(String label) {
            this.label = label;
        }

        @SuppressWarnings("unused")
        public AbstractProject<?, ?> getOwner() {
            return (AbstractProject) job;
        }

        @Override
        public String getIconFileName() {
            return "clipboard.gif";
        }

        @Override
        public String getDisplayName() {
            return "IvyTrigger Log";
        }

        @Override
        public String getUrlName() {
            return "ivyTriggerPollLog";
        }

        @SuppressWarnings("unused")
        public String getLabel() {
            return label;
        }

        @SuppressWarnings("unused")
        public String getLog() throws IOException {
            return Util.loadFile(getLogFile());
        }

        @SuppressWarnings("unused")
        public void writeLogTo(XMLOutput out) throws IOException {
            new AnnotatedLargeText<InternalIvyTriggerAction>(getLogFile(), Charset.defaultCharset(), true, this).writeHtmlTo(0, out.asWriter());
        }
    }


    @Override
    public boolean isContextOnStartupFetched() {
        return false;
    }

    @Override
    protected IvyTriggerContext getContext(Node pollingNode, XTriggerLog log) throws XTriggerException {

        log.info(String.format("Given job Ivy file value: %s", ivyPath));
        log.info(String.format("Given job Ivy settings file value: %s", ivySettingsPath));

        AbstractProject project = (AbstractProject) job;
        EnvVarsResolver varsRetriever = new EnvVarsResolver();
        Map<String, String> envVars;
        try {
            envVars = varsRetriever.getPollingEnvVars(project, pollingNode);
        } catch (EnvInjectException e) {
            throw new XTriggerException(e);
        }

        //Get ivy file and get ivySettings file
        FilePathFactory filePathFactory = new FilePathFactory();
        FilePath ivyFilePath = filePathFactory.getDescriptorFilePath(ivyPath, project, pollingNode, log, envVars);
        FilePath ivySettingsFilePath = filePathFactory.getDescriptorFilePath(ivySettingsPath, project, pollingNode, log, envVars);

        if (ivyFilePath == null) {
            log.error("You have to provide a valid Ivy file.");
            return new IvyTriggerContext(null);
        }
        if (ivySettingsFilePath == null) {
            log.error("You have to provide a valid IvySettings file.");
            return new IvyTriggerContext(null);
        }

        log.info(String.format("Resolved job Ivy file value: %s", ivyFilePath.getRemote()));
        log.info(String.format("Resolved job Ivy settings file value: %s", ivySettingsFilePath.getRemote()));

        PropertiesFileContentExtractor propertiesFileContentExtractor = new PropertiesFileContentExtractor(new FilePathFactory());
        String propertiesFileContent = propertiesFileContentExtractor.extractPropertiesFileContents(propertiesFilePath, project, pollingNode, log, envVars);
        String propertiesContentResolved = Util.replaceMacro(propertiesContent, envVars);

        Map<String, IvyDependencyValue> dependencies;
        try {
            FilePath temporaryPropertiesFilePath = pollingNode.getRootPath().createTextTempFile("props", "props", propertiesFileContent);
            log.info("Temporary properties file path is " + temporaryPropertiesFilePath.getName());
            dependencies = getDependenciesMapForNode(pollingNode, log, ivyFilePath, ivySettingsFilePath, temporaryPropertiesFilePath, propertiesContentResolved, envVars);
            temporaryPropertiesFilePath.delete();
        } catch (IOException ioe) {
            throw new XTriggerException(ioe);
        } catch (InterruptedException ie) {
            throw new XTriggerException(ie);
        }
        return new IvyTriggerContext(dependencies);
    }
    
    @Override
    /**
     * I attempt to read the previous context from the job config directory on the master
     * server filesystem, and assign it to the in-memory context
     * @param log
     * @return true if the context was read from file
     */
    protected boolean readContextFromFile(XTriggerLog log) {
        
        if (isContextSerialized()) {
            try {
                File contextFile = new File (job.getRootDir(), "IvyTriggerContext.ser");
                log.info("The new serialised context File object points at: " + contextFile.getAbsolutePath());
                FileInputStream fileInputStream = new FileInputStream(contextFile);
                log.info("Successfully created FileInputStream to context file");
                ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
                log.info("Successfully created ObjectInputStream to FileInputStream for context file");
                IvyTriggerContext serializedContext = (IvyTriggerContext) objectInputStream.readObject();
                log.info("Successfully read serializedContext from  ObjectInputStream");
                this.resetOldContext(serializedContext);
                log.info("Successfully assigned serializedContext to this.context");
                objectInputStream.close();
                log.info("Successfully closed ObjectInputStream to FileInputStream for context file");
                return true;
            }
            catch (IOException ioException) {
                log.error("IOExeption while deserializing the previous IvyTriggerContext object: " + ioException.getMessage());
                return false;
            }
            catch (ClassNotFoundException classNotFoundException) {
                log.error("ClassNotFoundException while deserializing the previous IvyTriggerContext object: " + classNotFoundException.getMessage());
                return false;
            }
        } else {
            log.info("The current job (" + job.getDisplayName() + ") does not persist its dependency tree to disk");
            return false;
        }
        
    }

    private Map<String, IvyDependencyValue> getDependenciesMapForNode(Node launcherNode,
                                                                      XTriggerLog log,
                                                                      FilePath ivyFilePath,
                                                                      FilePath ivySettingsFilePath,
                                                                      FilePath propertiesFilePath,
                                                                      String propertiesContent,
                                                                      Map<String, String> envVars) throws IOException, InterruptedException, XTriggerException {
        Map<String, IvyDependencyValue> dependenciesMap = null;
        if (launcherNode != null) {
            FilePath launcherFilePath = launcherNode.getRootPath();
            if (launcherFilePath != null) {
                dependenciesMap = launcherFilePath.act(new IvyTriggerEvaluator(job.getName(), ivyFilePath, ivySettingsFilePath, propertiesFilePath, propertiesContent, log, debug, envVars));
            }
        }
        return dependenciesMap;
    }

    @Override
    protected String getName() {
        return "IvyTrigger";
    }

    @Override
    protected Action[] getScheduledActions(Node pollingNode, XTriggerLog log) {
        return new Action[0];
    }

    @Override
    protected boolean checkIfModified(IvyTriggerContext previousIvyTriggerContext,
                                      IvyTriggerContext newIvyTriggerContext,
                                      XTriggerLog log)
            throws XTriggerException {
        
        // if contextSerialized config is set, serialize the new context information to disk in the job's workspace
        if (isContextSerialized()) {
            log.info("Save resolved dependencies to disk is set to true (contextSerialized=true)");
            log.info("Attempting to serialize new IvyTriggerContext to job config directory");
            try {
                File contextFile = new File (job.getRootDir(), "IvyTriggerContext.ser");
                log.info("The new serialised context File object points at: " + contextFile.getAbsolutePath());
                contextFile.delete();
                log.info("Successfully deleted the serialised context file");
                FileOutputStream fileOutputStream = new FileOutputStream(contextFile);
                log.info("Successfully created FileOutputStream to context file");
                ObjectOutputStream out = new ObjectOutputStream(fileOutputStream);
                log.info("Successfully created ObjectOutputStream to FileOutputStream for context file");
                out.writeObject(newIvyTriggerContext);
                log.info("Successfully wrote newIvyTriggerContext to  ObjectOutputStream");
                out.close();
                log.info("Successfully closed ObjectOutputStream to FileOutputStream for context file");
            
                } catch (IOException e) {
                    log.error("IOException while serializing the new IvyTriggerContext object: " + e.getMessage());
                }
            }


        Map<String, IvyDependencyValue> previousDependencies = previousIvyTriggerContext.getDependencies();

        if (previousDependencies == null) {
            log.error("Can't compute files to check if there are modifications.");
            resetOldContext(previousIvyTriggerContext);
            return false;
        }

        Map<String, IvyDependencyValue> newComputedDependencies = newIvyTriggerContext.getDependencies();

        //Check pre-requirements
        if (newComputedDependencies == null) {
            log.error("Can't record the resolved dependencies graph.");
            resetOldContext(previousIvyTriggerContext);
            return false;
        }

        if (newComputedDependencies.size() == 0) {
            log.error("Can't record any dependencies. Check your settings.");
            resetOldContext(previousIvyTriggerContext);
            return false;
        }

        //Display all resolved dependencies
        for (Map.Entry<String, IvyDependencyValue> dependency : newComputedDependencies.entrySet()) {
            log.info(String.format("Resolved dependency %s ...", dependency.getKey()));
        }

        // set the new context
        setNewContext(newIvyTriggerContext);

        if (previousDependencies.size() != newComputedDependencies.size()) {
            log.info(String.format("\nThe number of resolved dependencies has changed. Were "+previousDependencies.size()+" Are "+newComputedDependencies.size()));
        }

        //Check and report if there are changes left to right
        int changesFound=0;
        log.info("\nChecking comparison to previous recorded dependencies.");
        for (Map.Entry<String, IvyDependencyValue> dependency : previousDependencies.entrySet()) {
            if (isDependencyChanged(log, dependency, newComputedDependencies)) {
                changesFound++;
            }
        }
        
        //Check and report if there are new dependencies not previously recorded (right to left)
        for (Map.Entry<String, IvyDependencyValue> newDependency : newComputedDependencies.entrySet()) {
			String[] newdependencyName = newDependency.getKey().split(";");
            boolean found=false;
			for (Map.Entry<String, IvyDependencyValue> previousDependency : previousDependencies.entrySet()) {
    			String previousdependencyName = previousDependency.getKey().split(";")[0];   
    			if(previousdependencyName.equals(newdependencyName[0])){
    				found=true;
    				break;
    			}
            }
			if(!found){
                changesFound++;
	            log.info(String.format("....The new dependency %s (%s) did not exist before.", newdependencyName[0],newdependencyName[1]));
			}       	
        }        
        

        return (changesFound>0);
    }

    private boolean isDependencyChanged(XTriggerLog log,
                                        Map.Entry<String, IvyDependencyValue> previousDependency,
                                        Map<String, IvyDependencyValue> newComputedDependencies) {

        String dependencyId = previousDependency.getKey();
        String dependencyName = dependencyId.split(";")[0];
        
        log.info(String.format("Checking previous recording dependency %s", dependencyId));

        IvyDependencyValue previousDependencyValue = previousDependency.getValue();
        IvyDependencyValue newDependencyValue = newComputedDependencies.get(dependencyId);
        
		// if we find nothing try to search just the name ignoring the revision.
		if (newDependencyValue == null) {
	        //log.info(String.format("Failed to find the new dependency for %s. Will try using just the name.",dependencyName));
			// try to detect changes in the revision number even if a fixed
			// revision is provided
			for (Entry<String, IvyDependencyValue> newDepEntry : newComputedDependencies.entrySet()) {
				String newdependencyName = newDepEntry.getKey().split(";")[0];
		        //log.info(String.format(".....Comparing dependency %s with name %s with the old dependency name %s ",newDepEntry.getKey(),newdependencyName,dependencyName));
				if (newdependencyName.equalsIgnoreCase(dependencyName)) {
					newDependencyValue = newDepEntry.getValue();
			        //log.info(".....Match found! New Dependency Value: "+newDependencyValue);
					break;
				}
			}
		}
            
        //Check if the previous dependency exists anymore
        if (newDependencyValue == null) {
            log.info(String.format("....The previous dependency %s doesn't exist anymore.", dependencyId));
            return true;
        }

        //Check if the revision has changed
        String previousRevision = previousDependencyValue.getRevision();
        String newRevision = newDependencyValue.getRevision();
        log.info(String.format("The previous version recorded was %s.", previousRevision));
        log.info(String.format("The new computed version is %s.", newRevision));
        if (!newRevision.equals(previousRevision)) {
            log.info("....The dependency version has changed for "+dependencyName+" .");
            log.info(String.format("....The previous version recorded was %s.", previousRevision));
            log.info(String.format("....The new computed version is %s.", newRevision));
            return true;
        }

        //Check if artifacts list has changed
        List<IvyArtifactValue> previousArtifactValueList = previousDependencyValue.getArtifacts();
        List<IvyArtifactValue> newArtifactValueList = newDependencyValue.getArtifacts();

        //Display all resolved artifacts
        for (IvyArtifactValue artifactValue : newArtifactValueList) {
            log.info(String.format("..Dependency resolved artifact: %s", artifactValue.getFullName()));
        }

        if (previousArtifactValueList.size() != newArtifactValueList.size()) {
            log.info("....The number of artifacts of the dependency has changed.");
        }

        //Check if there is at least one change to previous recording artifacts
        log.info("...Checking comparison to previous recorded artifacts.");
        int artifactsChanged=0;
        for (IvyArtifactValue ivyArtifactValue : previousArtifactValueList) {
            if (isArtifactsChanged(log, ivyArtifactValue, newArtifactValueList)) {
            	artifactsChanged++;
            }
        }

        return (artifactsChanged>0);
    }

    private boolean isArtifactsChanged(XTriggerLog log, IvyArtifactValue previousIvyArtifactValue, List<IvyArtifactValue> newArtifactValueList) {

        log.info(String.format("....Checking previous recording artifact %s", previousIvyArtifactValue.getFullName()));

        //Get the new artifact with same coordinates
        IvyArtifactValue newIvyArtifactValue = null;
        boolean stop = false;
        int i = 0;
        while (!stop && i < newArtifactValueList.size()) {
            IvyArtifactValue ivyArtifactValue = newArtifactValueList.get(i);
            if (ivyArtifactValue.getFullName().equals(previousIvyArtifactValue.getFullName())) {
                newIvyArtifactValue = ivyArtifactValue;
                stop = true;
            }
            i++;
        }

        //--Check if there are changes

        //Check if the artifact still exist
        if (newIvyArtifactValue == null) {
            log.info(String.format("....The previous artifact %s doesn't exist anymore.", previousIvyArtifactValue.getFullName()));
            return true;
        }

        //Check the publication date
        long previousPublicationDate = previousIvyArtifactValue.getLastModificationDate();
        long newPublicationDate = newIvyArtifactValue.getLastModificationDate();
        if (previousPublicationDate != newPublicationDate) {
            log.info("....The artifact version of the dependency has changed.");
            log.info(String.format("....The previous publication date recorded was %s.", new Date(previousPublicationDate)));
            log.info(String.format("....The new computed publication date is %s.", new Date(newPublicationDate)));
            return true;
        }

        log.info(String.format("....No changes for the %s artifact", newIvyArtifactValue.getFullName()));
        return false;
    }


    /**
     * Gets the triggering log file
     *
     * @return the trigger log
     */
    protected File getLogFile() {
        return new File(job.getRootDir(), "ivy-polling.log");
    }

    @Override
    protected boolean requiresWorkspaceForPolling() {
        return true;
    }

    @Override
    public String getCause() {
        return "Ivy Dependency trigger";
    }

    @Extension
    @SuppressWarnings("unused")
    public static class IvyScriptTriggerDescriptor extends XTriggerDescriptor {

        @Override
        public String getHelpFile() {
            return "/plugin/ivytrigger/help.html";
        }

        @Override
        public String getDisplayName() {
            return "[IvyTrigger] - Poll with an Ivy script";
        }
    }

}
