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


package org.hornetq.core.server.cluster;

import org.hornetq.core.client.MessageHandler;
import org.hornetq.core.server.Queue;

/**
 * A MessageFlowRecord
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * 
 * Created 5 Feb 2009 11:39:36
 *
 *
 */
public interface MessageFlowRecord extends MessageHandler
{
   String getAddress();
   
   int getMaxHops();
   
   void activate(Queue queue) throws Exception;
   
   //void reset() throws Exception;
   
   void close() throws Exception;
}