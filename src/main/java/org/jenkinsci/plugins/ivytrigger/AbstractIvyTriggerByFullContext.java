/*
 * Gaia CU5 DU10
 *
 * (c) 2005-2020 Gaia Data Processing and Analysis Consortium
 *
 *
 * CU5 photometric calibration software is free software; you can redistribute
 * it and/or modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * CU5 photometric calibration software is distributed in the hope that it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this CU5 software; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 *
 *-----------------------------------------------------------------------------
 */

package org.jenkinsci.plugins.ivytrigger;

import hudson.model.BuildableItem;
import hudson.model.Node;

import org.jenkinsci.lib.xtrigger.AbstractTrigger;
import org.jenkinsci.lib.xtrigger.XTriggerContext;
import org.jenkinsci.lib.xtrigger.XTriggerException;
import org.jenkinsci.lib.xtrigger.XTriggerLog;

import antlr.ANTLRException;

/**
 * I am a temporary, amended local copy of the AbstractTriggerByFullContext necessary to allow
 * the checkIfModified method to try and load its previous context from a serialized file on disk
 * if the in-memory IvyTriggerContext object is null
 *
 * @author imacdona
 * @version $Id: AbstractIvyTriggerByFullContext.java 422936 2015-03-24 17:02:18Z imacdona $
 */
public abstract class AbstractIvyTriggerByFullContext<C extends XTriggerContext> extends AbstractTrigger {

    private transient C context;

    private transient Object lock = new Object();

    /**
     * Builds a trigger object
     * Calls an implementation trigger
     *
     * @param cronTabSpec the scheduler value
     * @throws ANTLRException the expression language expression
     */
    public AbstractIvyTriggerByFullContext(String cronTabSpec) throws ANTLRException {
        super(cronTabSpec);
    }

    protected AbstractIvyTriggerByFullContext(String cronTabSpec, boolean unblockConcurrentBuild) throws ANTLRException {
        super(cronTabSpec, unblockConcurrentBuild);
    }

    protected AbstractIvyTriggerByFullContext(String cronTabSpec, String triggerLabel) throws ANTLRException {
        super(cronTabSpec, triggerLabel);
    }

    protected AbstractIvyTriggerByFullContext(String cronTabSpec, String triggerLabel, boolean unblockConcurrentBuild) throws ANTLRException {
        super(cronTabSpec, triggerLabel, unblockConcurrentBuild);
    }

    /**
     * Can be overridden if needed
     */
    @Override
    protected void start(Node pollingNode, BuildableItem project, boolean newInstance, XTriggerLog log) throws XTriggerException {
        if (isContextOnStartupFetched()) {
            context = getContext(pollingNode, log);
        }
    }

    public abstract boolean isContextOnStartupFetched();

    @Override
    protected boolean checkIfModified(Node pollingNode, XTriggerLog log) throws XTriggerException {

        // make sure the lock is not null; when de-serialising
        if(lock==null){
            lock = new Object();
        }
        
        synchronized (lock) {

            C newContext = getContext(pollingNode, log);

            if (offlineSlaveOnStartup) {
                log.info("No nodes were available at startup or at previous poll.");
                log.info("Attempting to load old environment context from disk before checking if there are modifications.");
                boolean contextWasReadFromFile = readContextFromFile(log);
                offlineSlaveOnStartup = false;
                if (!contextWasReadFromFile) {
                    log.info("Old environment context was not read from disk: recording new context in-memory and checking changes in next poll.");
                    setNewContext(newContext);
                    return false;
                }
            }

            if (context == null) {
                log.info("Old environment context in-memory is null.");
                log.info("Attempting to load old environment context from disk before checking if there are modifications.");
                boolean contextWasReadFromFile = readContextFromFile(log);
                if (!contextWasReadFromFile) {
                    log.info("Old environment context was not read from disk: recording new context in-memory and checking changes in next poll.");
                    setNewContext(newContext);
                    return false;
                }
            }

            boolean changed = checkIfModified(context, newContext, log);
            return changed;
        }
    }

    @Override
    protected boolean checkIfModified(XTriggerLog log) throws XTriggerException {
        
        // make sure the lock is not null; when de-serialising
        if(lock==null){
            lock = new Object();
        }
        
        synchronized (lock) {
            C newContext = getContext(log);

            if (context == null) {
                log.info("Old environment context in-memory is null.");
                log.info("Attempting to load old environment context from disk before checking if there are modifications.");
                boolean contextWasReadFromFile = readContextFromFile(log);
                if (!contextWasReadFromFile) {
                    log.info("Old environment context was not read from disk: recording new context in-memory and checking changes in next poll.");
                    setNewContext(newContext);
                    return false;
                }
            }

            boolean changed = checkIfModified(context, newContext, log);
            return changed;
        }
    }
    
    /**
     * I attempt to read the previous context from the job config directory on the master
     * server filesystem, and assign it to the in-memory context
     * @param log
     * @return true if the context was read from file
     */
    protected abstract boolean readContextFromFile(XTriggerLog log);

    protected void setNewContext(C context) {
        
         // make sure the lock is not null; when de-serialising
        if(lock==null){
            lock = new Object();
        }
        
        synchronized (lock) {
            this.context = context;
        }
    }

    /**
     * Resets the current context to the old context
     *
     * @param oldContext the previous context
     */
    protected void resetOldContext(C oldContext) {
        
         // make sure the lock is not null; when de-serialising
        if(lock==null){
            lock = new Object();
        }
        
        synchronized (lock) {
            this.context = oldContext;
        }
    }

    /**
     * Captures the context
     * This method is alternative to getContext(XTriggerLog log)
     * It must be overridden
     * from 0.26
     */
    protected C getContext(Node pollingNode, XTriggerLog log) throws XTriggerException {
        return null;
    }

    /**
     * Captures the context
     * This method is alternative to getContext(Node pollingNode, XTriggerLog log)
     * It must be overridden
     * from 0.26
     */
    protected C getContext(XTriggerLog log) throws XTriggerException {
        return null;
    }

    /**
     * Checks if there are modifications in the environment between last poll
     *
     * @return true if there are modifications
     */
    protected abstract boolean checkIfModified(C oldContext, C newContext, XTriggerLog log) throws XTriggerException;

}

