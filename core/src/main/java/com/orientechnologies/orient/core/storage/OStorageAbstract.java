/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */
package com.orientechnologies.orient.core.storage;

import com.orientechnologies.common.concur.resource.OCloseable;
import com.orientechnologies.common.concur.resource.OSharedContainerImpl;
import com.orientechnologies.common.concur.resource.OSharedResource;
import com.orientechnologies.common.concur.resource.OSharedResourceAdaptiveExternal;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.config.OStorageConfiguration;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.record.OCurrentStorageComponentsFactory;
import com.orientechnologies.orient.core.exception.OSecurityException;
import com.orientechnologies.orient.core.metadata.OMetadata;
import com.orientechnologies.orient.core.metadata.OMetadataInternal;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.security.OSecurityShared;
import com.orientechnologies.orient.core.serialization.serializer.OStringSerializerHelper;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

public abstract class OStorageAbstract extends OSharedContainerImpl implements OStorage {
  protected final String                              url;
  protected final String                              mode;
  protected final OSharedResourceAdaptiveExternal     lock;
  protected volatile OStorageConfiguration            configuration;
  protected volatile OCurrentStorageComponentsFactory componentsFactory;
  protected String                                    name;
  protected AtomicLong                                version = new AtomicLong();
  protected volatile STATUS                           status  = STATUS.CLOSED;

  public OStorageAbstract(final String name, final String iURL, final String mode, final int timeout) {
    if (OStringSerializerHelper.contains(name, '/'))
      this.name = name.substring(name.lastIndexOf("/") + 1);
    else
      this.name = name;

    if (OStringSerializerHelper.contains(name, ','))
      throw new IllegalArgumentException("Invalid character in storage name: " + this.name);

    url = iURL;
    this.mode = mode;

    lock = new OSharedResourceAdaptiveExternal(OGlobalConfiguration.ENVIRONMENT_CONCURRENT.getValueAsBoolean(), timeout, true);
  }

  public abstract OCluster getClusterByName(final String iClusterName);

  public OStorage getUnderlying() {
    return this;
  }

  public OStorageConfiguration getConfiguration() {
    return configuration;
  }

  public boolean isClosed() {
    return status == STATUS.CLOSED;
  }

  public boolean checkForRecordValidity(final OPhysicalPosition ppos) {
    return ppos != null && !ppos.recordVersion.isTombstone();
  }

  public String getName() {
    return name;
  }

  public String getURL() {
    return url;
  }

  public void close() {
    close(false, false);
  }

  public void close(final boolean iForce, boolean onDelete) {
    lock.acquireExclusiveLock();
    try {
      for (Object resource : sharedResources.values()) {
        if (resource instanceof OSharedResource)
          ((OSharedResource) resource).releaseExclusiveLock();

        if (resource instanceof OCloseable)
          ((OCloseable) resource).close(onDelete);
      }
      sharedResources.clear();
    } finally {
      lock.releaseExclusiveLock();
    }
  }

  /**
   * Returns current storage's version as serial.
   */
  public long getVersion() {
    return version.get();
  }

  public boolean dropCluster(final String iClusterName, final boolean iTruncate) {
    return dropCluster(getClusterIdByName(iClusterName), iTruncate);
  }

  public int getUsers() {
    return lock.getUsers();
  }

  public int addUser() {
    return lock.addUser();
  }

  public int removeUser() {
    return lock.removeUser();
  }

  public OSharedResourceAdaptiveExternal getLock() {
    return lock;
  }

  public long countRecords() {
    long tot = 0;

    for (OCluster c : getClusterInstances())
      if (c != null)
        tot += c.getEntries() - c.getTombstonesCount();

    return tot;
  }

  public <V> V callInLock(final Callable<V> iCallable, final boolean iExclusiveLock) {
    if (iExclusiveLock)
      lock.acquireExclusiveLock();
    else
      lock.acquireSharedLock();
    try {
      return iCallable.call();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new OException("Error on nested call in lock", e);
    } finally {
      if (iExclusiveLock)
        lock.releaseExclusiveLock();
      else
        lock.releaseSharedLock();
    }
  }

  @Override
  public String toString() {
    return url != null ? url : "?";
  }

  public STATUS getStatus() {
    return status;
  }

  public void checkForClusterPermissions(final String iClusterName) {
    // CHECK FOR ORESTRICTED
    OMetadata metaData = ODatabaseRecordThreadLocal.INSTANCE.get().getMetadata();
    if (metaData != null) {
      final Set<OClass> classes = ((OMetadataInternal)metaData).getImmutableSchemaSnapshot().getClassesRelyOnCluster(iClusterName);
      for (OClass c : classes) {
        if (c.isSubClassOf(OSecurityShared.RESTRICTED_CLASSNAME))
          throw new OSecurityException("Class " + c.getName()
              + " cannot be truncated because has record level security enabled (extends " + OSecurityShared.RESTRICTED_CLASSNAME
              + ")");
      }
    }
  }

  @Override
  public boolean isDistributed() {
    return false;
  }

  @Override
  public boolean isAssigningClusterIds() {
    return true;
  }

  @Override
  public OCurrentStorageComponentsFactory getComponentsFactory() {
    return componentsFactory;
  }

  @Override
  public long getLastOperationId() {
    return 0;
  }

  protected boolean checkForClose(final boolean force) {
    if (status == STATUS.CLOSED)
      return false;

    lock.acquireSharedLock();
    try {
      if (status == STATUS.CLOSED)
        return false;

      final int remainingUsers = getUsers() > 0 ? removeUser() : 0;

      return force || (!(this instanceof OAbstractPaginatedStorage) && remainingUsers == 0);
    } finally {
      lock.releaseSharedLock();
    }
  }
}
