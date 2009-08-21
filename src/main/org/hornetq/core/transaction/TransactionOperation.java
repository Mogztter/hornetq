/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */ 

package org.hornetq.core.transaction;

import java.util.Collection;

import org.hornetq.core.server.Queue;

/**
 * 
 * A TransactionOperation
 * 
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 *
 */
public interface TransactionOperation
{
   
   /** rollback will need a distinct list of Queues in order to lock those queues before calling rollback */
   Collection<Queue> getDistinctQueues();
   
   void beforePrepare(Transaction tx) throws Exception;
   
   void beforeCommit(Transaction tx) throws Exception;
   
   void beforeRollback(Transaction tx) throws Exception;
   
   void afterPrepare(Transaction tx) throws Exception;
      
   void afterCommit(Transaction tx) throws Exception;
   
   void afterRollback(Transaction tx) throws Exception;   
}