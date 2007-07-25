/*
 * Copyright (c) 2007, SQL Power Group Inc.
 * 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in
 *       the documentation and/or other materials provided with the
 *       distribution.
 *     * Neither the name of SQL Power Group Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package ca.sqlpower.architect.swingui;

import java.io.File;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.swing.Action;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.ProgressMonitor;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

import org.apache.log4j.Logger;

import ca.sqlpower.architect.ArchitectException;
import ca.sqlpower.architect.ArchitectUtils;
import ca.sqlpower.architect.CoreUserSettings;
import ca.sqlpower.architect.SQLDatabase;
import ca.sqlpower.architect.SQLObject;
import ca.sqlpower.architect.SQLObjectEvent;
import ca.sqlpower.architect.SQLObjectListener;
import ca.sqlpower.architect.UserSettings;
import ca.sqlpower.architect.ddl.GenericDDLGenerator;
import ca.sqlpower.architect.etl.kettle.CreateKettleJob;
import ca.sqlpower.architect.profile.ProfileManager;
import ca.sqlpower.architect.profile.TableProfileManager;
import ca.sqlpower.architect.swingui.action.AboutAction;
import ca.sqlpower.architect.swingui.action.PreferencesAction;
import ca.sqlpower.architect.swingui.event.PlayPenComponentEvent;
import ca.sqlpower.architect.swingui.event.PlayPenComponentListener;
import ca.sqlpower.architect.swingui.event.SessionLifecycleEvent;
import ca.sqlpower.architect.undo.UndoManager;

public class ArchitectSwingSessionImpl implements ArchitectSwingSession {

    private static final Logger logger = Logger.getLogger(ArchitectSwingSessionImpl.class);
    
    private final ArchitectSwingSessionContext context;

    /**
     * The project associated with this session.  The project provides save
     * and load functionality, and houses the source database connections.
     */
    private final SwingUIProject project;

    /**
     * The Frame where the main part of the GUI for this session appears.
     */
    private ArchitectFrame frame;

    private CoreUserSettings userSettings;
    
    private TableProfileManager profileManager;
    
    // STUFF BROUGHT IN FROM SwingUIProject
    /** the dialog that contains the small ProfileManagerView */
    private JDialog profileDialog;
    
    private PlayPen playPen;
    
    /** the small dialog that lists the profiles */
    private ProfileManagerView profileManagerView;
    
    private DBTree sourceDatabases;
    
    private String name;
    
    private GenericDDLGenerator ddlGenerator;
    
    private CompareDMSettings compareDMSettings;

    private UndoManager undoManager;
    
    private boolean savingEntireSource;
    
    private CreateKettleJob createKettleJob;
    // END STUFF BROUGHT IN FROM SwingUIProject
    
    private List<SessionLifecycleListener> lifecycleListener;
    
    /**
     * Creates a new swing session, including a new visible architect frame, with
     * the given parent context and the given name.
     * 
     * @param context
     * @param name
     * @throws ArchitectException
     */
    ArchitectSwingSessionImpl(ArchitectSwingSessionContext context, String name)
    throws ArchitectException {

        this.context = context;
        this.name = name;

        userSettings = context.getUserSettings();
        
        // Make sure we can load the pl.ini file so we can handle exceptions
        // XXX this is probably redundant now, since the context owns the pl.ini
        userSettings.getPlDotIni();

        project = new SwingUIProject(this);

        profileManager = new TableProfileManager();


        try {
            ddlGenerator = new GenericDDLGenerator();
        } catch (SQLException e) {
            throw new ArchitectException("SQL Error in ddlGenerator",e);
        }
        compareDMSettings = new CompareDMSettings();
        
        createKettleJob = new CreateKettleJob();
        
        SQLDatabase ppdb = new SQLDatabase();
        playPen = new PlayPen(this, ppdb);

        List initialDBList = new ArrayList();
        initialDBList.add(playPen.getDatabase());
        this.sourceDatabases = new DBTree(this, initialDBList);
        
        undoManager = new UndoManager(playPen);
        
        lifecycleListener = new ArrayList<SessionLifecycleListener>();
    }

    public void initGUI() throws ArchitectException {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("This method must be called on the Swing Event Dispatch Thread.");
        }

        ToolTipManager.sharedInstance().registerComponent(playPen);
        
        profileDialog = new JDialog(frame, "Table Profiles");
        profileManagerView = new ProfileManagerView(profileManager);
        profileManager.addProfileChangeListener(profileManagerView);
        profileDialog.add(profileManagerView);

        frame = new ArchitectFrame(this, project);
        
        // MUST be called after constructed to set up the actions
        frame.init(); 
        frame.setVisible(true);
        
        // This has to be called after frame.init() because playPen gets the keyboard actions from frame,
        // which only get set up after calling frame.init().
        playPen.setupKeyboardActions();
        
        macOSXRegistration(frame);
        
        profileDialog.setLocationRelativeTo(frame);
    }

    public SwingUIProject getProject() {
        return project;
    }

    public CoreUserSettings getUserSettings() {
        return userSettings;
    }

    public void setUserSettings(CoreUserSettings argUserSettings) {
        this.userSettings = argUserSettings;
    }

    public ArchitectSwingSessionContext getContext() {
        return context;
    }

    public ArchitectFrame getArchitectFrame() {
        return frame;
    }
    
    /**
     * Registers this application in Mac OS X if we're running on that platform.
     *
     * <p>This code came from Apple's "OS X Java Adapter" example.
     */
    private void macOSXRegistration(ArchitectFrame frame) {
        
        
        Action exitAction = frame.getExitAction();
        PreferencesAction prefAction = frame.getPrefAction();
        AboutAction aboutAction = frame.getAboutAction();

        // Whether or not this is OS X, the three actions we're referencing must have been initialized by now.
        if (exitAction == null) throw new IllegalStateException("Exit action has not been initialized");
        if (prefAction == null) throw new IllegalStateException("Prefs action has not been initialized");
        if (aboutAction == null) throw new IllegalStateException("About action has not been initialized");

        if (context.isMacOSX()) {
            try {
                Class osxAdapter = ClassLoader.getSystemClassLoader().loadClass("ca.sqlpower.architect.swingui.OSXAdapter");

                // The main registration method.  Takes quitAction, prefsAction, aboutAction.
                Class[] defArgs = { Action.class, Action.class, Action.class };
                Method registerMethod = osxAdapter.getDeclaredMethod("registerMacOSXApplication", defArgs);
                Object[] args = { exitAction, prefAction, aboutAction };
                registerMethod.invoke(osxAdapter, args);

                // The enable prefs method.  Takes a boolean.
                defArgs = new Class[] { boolean.class };
                Method prefsEnableMethod =  osxAdapter.getDeclaredMethod("enablePrefs", defArgs);
                args = new Object[] {Boolean.TRUE};
                prefsEnableMethod.invoke(osxAdapter, args);
            } catch (NoClassDefFoundError e) {
                // This will be thrown first if the OSXAdapter is loaded on a system without the EAWT
                // because OSXAdapter extends ApplicationAdapter in its def
                System.err.println("This version of Mac OS X does not support the Apple EAWT.  Application Menu handling has been disabled (" + e + ")");
            } catch (ClassNotFoundException e) {
                // This shouldn't be reached; if there's a problem with the OSXAdapter we should get the
                // above NoClassDefFoundError first.
                System.err.println("This version of Mac OS X does not support the Apple EAWT.  Application Menu handling has been disabled (" + e + ")");
            } catch (Exception e) {
                System.err.println("Exception while loading the OSXAdapter:");
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Checks if the project is modified, and if so presents the user with the option to save
     * the existing project.  This is useful to use in actions that are about to get rid of
     * the currently open project.
     *
     * @return True if the project can be closed; false if the project should remain open.
     */
    protected boolean promptForUnsavedModifications() {
        if (getProject().isModified()) {
            int response = JOptionPane.showOptionDialog(frame,
                    "Your project has unsaved changes", "Unsaved Changes",
                    JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    new Object[] {"Don't Save", "Cancel", "Save"}, "Save");
            if (response == 0) {
                return true;
            } else if (response == JOptionPane.CLOSED_OPTION || response == 1) {
                return false;
            } else {
                return saveOrSaveAs(false, false);
            }
        } else {
            return true;
        }
    }

    /**
     * Condition the Model to save the project, showing a file chooser when appropriate.
     *
     * @param showChooser If true, a chooser will always be shown; otherwise a
     * chooser will only be shown if the project has no file associated with it
     * (this is usually because it has never been saved before).
     * @param separateThread If true, the (possibly lengthy) save operation
     * will be executed in a separate thread and this method will return immediately.
     * Otherwise, the save operation will proceed on the current thread.
     * @return True if the project was saved successfully; false otherwise.  If saving
     * on a separate thread, a result of <code>true</code> is just an optimistic guess,
     * and there is no way to discover if the save operation has eventually succeeded or
     * failed.
     */
    public boolean saveOrSaveAs(boolean showChooser, boolean separateThread) {
        SwingUIProject project = getProject();
        
        if (project.getFile() == null || showChooser) {
            JFileChooser chooser = new JFileChooser(project.getFile());
            chooser.addChoosableFileFilter(ASUtils.ARCHITECT_FILE_FILTER);
            int response = chooser.showSaveDialog(frame);
            if (response != JFileChooser.APPROVE_OPTION) {
                return false;
            } else {
                File file = chooser.getSelectedFile();
                if (!file.getPath().endsWith(".architect")) {
                    file = new File(file.getPath()+".architect");
                }
                if (file.exists()) {
                    response = JOptionPane.showConfirmDialog(
                            frame,
                            "The file\n\n"+file.getPath()+"\n\nalready exists. Do you want to overwrite it?",
                            "File Exists", JOptionPane.YES_NO_OPTION);
                    if (response == JOptionPane.NO_OPTION) {
                        return saveOrSaveAs(true, separateThread);
                    }
                }
                project.setFile(file);
                String projName = file.getName().substring(0, file.getName().length()-".architect".length());
                setName(projName);
                frame.setTitle(projName);
            }
        }
        final boolean finalSeparateThread = separateThread;
        final ProgressMonitor pm = new ProgressMonitor
            (frame, "Saving Project", "", 0, 100);

        class SaverTask implements Runnable {
            boolean success;

            public void run() {
                SwingUIProject project = getProject();
                try {
                    success = false;
                    project.setSaveInProgress(true);
                    project.save(finalSeparateThread ? pm : null);
                    success = true;
                    JOptionPane.showMessageDialog(frame, "Save successful");
                } catch (Exception ex) {
                    success = false;
                    ASUtils.showExceptionDialog(
                            ArchitectSwingSessionImpl.this,
                            "Can't save project: "+ex.getMessage(), ex);
                } finally {
                    project.setSaveInProgress(false);
                }
            }
        }
        SaverTask saveTask = new SaverTask();
        if (separateThread) {
            new Thread(saveTask).start();
            return true; // this is an optimistic lie
        } else {
            saveTask.run();
            return saveTask.success;
        }
    }

    // STUFF BROUGHT IN FROM SwingUIProject
    
    /**
     * This is a common handler for all actions that must occur when switching
     * projects, e.g., prompting to save any unsaved changes, disposing dialogs,
     * shutting down running threads, and so on.
     */
    public void close() {

        // IMPORTANT NOTE: If the GUI hasn't been initialized, frame will be null.
        
        if (getProject().isSaveInProgress()) {
            // project save is in progress, don't allow exit
            JOptionPane.showMessageDialog(frame,
                    "Project is saving, cannot exit the Power Architect.\n" +
                    "Please wait for the save to finish, and then try again.",
                    "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (!promptForUnsavedModifications()) {
            return;
        }
        
        try {
            if (frame != null) {
                // XXX this could/should be done by the frame with a session closing listener
                frame.saveSettings();
            }
        } catch (ArchitectException e) {
            logger.error("Couldn't save settings: "+e);
        }

        if (profileDialog != null) {
            // XXX this could/should be done by the profile dialog with a session closing listener
            profileDialog.dispose();
        }
        
        // It is possible this method will be called again via indirect recursion
        // because the frame has a windowClosing listener that calls session.close().
        // It should be harmless to have this close() method invoked a second time.
        if (frame != null) {
            // XXX this could/should be done by the frame with a session closing listener
            frame.dispose();
        }

        // close connections
        Iterator it = getSourceDatabases().getDatabaseList().iterator();
        while (it.hasNext()) {
            SQLDatabase db = (SQLDatabase) it.next();
            logger.debug ("closing connection: " + db.getName());
            db.disconnect();
        }
        
        // Clear the profile manager (the effect we want is just to cancel running profiles.. clearing is a harmless side effect)
        // XXX this could/should be done by the profile manager with a session closing listener
        profileManager.clear();
        
        fireSessionClosing();
    }
    
    /**
     * Gets the value of sourceDatabases
     *
     * @return the value of sourceDatabases
     */
    public DBTree getSourceDatabases()  {
        return this.sourceDatabases;
    }
    
    /**
     * Sets the value of sourceDatabases
     *
     * @param argSourceDatabases Value to assign to this.sourceDatabases
     */
    public void setSourceDatabases(DBTree argSourceDatabases) {
        this.sourceDatabases = argSourceDatabases;
    }

    public void setSourceDatabaseList(List databases) throws ArchitectException {
        this.sourceDatabases.setModel(new DBTreeModel(databases,this));
    }

    /**
     * Gets the target database in the playPen.
     */
    public SQLDatabase getTargetDatabase()  {
        return playPen.getDatabase();
    }
    
    /**
     * Sets the value of playPen
     *
     * @param argPlayPen Value to assign to this.playPen
     */
    public void setPlayPen(PlayPen argPlayPen) {
        this.playPen = argPlayPen;
        UserSettings sprefs = userSettings.getSwingSettings();
        if (sprefs != null) {
            playPen.setRenderingAntialiased(sprefs.getBoolean(SwingUserSettings.PLAYPEN_RENDER_ANTIALIASED, false));
        }
        new ProjectModificationWatcher(playPen);
    }
    
    /**
     * The ProjectModificationWatcher watches a PlayPen's components and
     * business model for changes.  When it detects any, it marks the
     * project dirty.
     *
     * <p>Note: when we implement proper undo/redo support, this class should
     * be replaced with a hook into that system.
     */
    private class ProjectModificationWatcher implements SQLObjectListener, PlayPenComponentListener {

        /**
         * Sets up a new modification watcher on the given playpen.
         */
        public ProjectModificationWatcher(PlayPen pp) {
            try {
                ArchitectUtils.listenToHierarchy(this, pp.getDatabase());
            } catch (ArchitectException e) {
                logger.error("Can't listen to business model for changes", e);
            }
            PlayPenContentPane ppcp = pp.contentPane;
            ppcp.addPlayPenComponentListener(this);
        }

        /** Marks project dirty, and starts listening to new kids. */
        public void dbChildrenInserted(SQLObjectEvent e) {
            project.setModified(true);
            SQLObject[] newKids = e.getChildren();
            for (int i = 0; i < newKids.length; i++) {
                try {
                    ArchitectUtils.listenToHierarchy(this, newKids[i]);
                } catch (ArchitectException e1) {
                    logger.error("Couldn't listen to SQLObject hierarchy rooted at "+newKids[i], e1);
                }
            }
        }

        /** Marks project dirty, and stops listening to removed kids. */
        public void dbChildrenRemoved(SQLObjectEvent e) {
            project.setModified(true);
            SQLObject[] oldKids = e.getChildren();
            for (int i = 0; i < oldKids.length; i++) {
                oldKids[i].removeSQLObjectListener(this);
            }
        }

        /** Marks project dirty. */
        public void dbObjectChanged(SQLObjectEvent e) {
            project.setModified(true);
        }

        /** Marks project dirty and listens to new hierarchy. */
        public void dbStructureChanged(SQLObjectEvent e) {
            try {
                ArchitectUtils.listenToHierarchy(this, e.getSQLSource());
            } catch (ArchitectException e1) {
                logger.error("dbStructureChanged listener: Failed to listen to new project hierarchy", e1);
            }
        }

        public void componentMoved(PlayPenComponentEvent e) {

        }

        public void componentResized(PlayPenComponentEvent e) {
            project.setModified(true);
        }

        public void componentMoveStart(PlayPenComponentEvent e) {
            project.setModified(true);
        }

        public void componentMoveEnd(PlayPenComponentEvent e) {
            project.setModified(true);
        }
    }
    
    /**
     * Gets the value of name
     *
     * @return the value of name
     */
    public String getName()  {
        return this.name;
    }

    /**
     * Sets the value of name
     *
     * @param argName Value to assign to this.name
     */
    public void setName(String argName) {
        this.name = argName;
    }
    
    /**
     * Gets the value of playPen
     *
     * @return the value of playPen
     */
    public PlayPen getPlayPen()  {
        return this.playPen;
    }

    public GenericDDLGenerator getDDLGenerator() {
        return ddlGenerator;
    }

    public void setDDLGenerator(GenericDDLGenerator generator) {
        ddlGenerator = generator;
    }

    public CompareDMSettings getCompareDMSettings() {
        return compareDMSettings;
    }
    public void setCompareDMSettings(CompareDMSettings compareDMSettings) {
        this.compareDMSettings = compareDMSettings;
    }
    
    public UndoManager getUndoManager() {
        return undoManager;
    }

    public ProfileManager getProfileManager() {
        return profileManager;
    }
    
    public JDialog getProfileDialog() {
        // Do the pack here in case this is the first time ever.
        profileDialog.pack();
        return profileDialog;
    }
    

    /**
     * See {@link #savingEntireSource}.
     *
     * @return the value of savingEntireSource
     */
    public boolean isSavingEntireSource()  {
        return this.savingEntireSource;
    }

    /**
     * See {@link #savingEntireSource}.
     *
     * @param argSavingEntireSource Value to assign to this.savingEntireSource
     */
    public void setSavingEntireSource(boolean argSavingEntireSource) {
        this.savingEntireSource = argSavingEntireSource;
    }
    
    public CreateKettleJob getCreateKettleJob() {
        return createKettleJob;
    }
    public void setCreateKettleJobSettings(CreateKettleJob createKettleJobSettings) {
        this.createKettleJob = createKettleJobSettings;
    }
    // END STUFF BROUGHT IN FROM SwingUIProject

    public void addSessionLifecycleListener(SessionLifecycleListener listener) {
        lifecycleListener.add(listener);
    }
    
    public void fireSessionClosing() {
        SessionLifecycleEvent evt = new SessionLifecycleEvent(this);
        for (SessionLifecycleListener listener: lifecycleListener) {
            listener.sessionClosing(evt);
        }
    }
}