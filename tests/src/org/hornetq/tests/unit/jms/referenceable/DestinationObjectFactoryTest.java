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

package org.hornetq.tests.unit.jms.referenceable;

import static org.hornetq.tests.util.RandomUtil.randomString;

import javax.naming.Reference;

import org.hornetq.jms.HornetQDestination;
import org.hornetq.jms.HornetQQueue;
import org.hornetq.jms.referenceable.DestinationObjectFactory;
import org.hornetq.tests.util.UnitTestCase;

/**
 * @author <a href="mailto:jmesnil@redhat.com">Jeff Mesnil</a>
 *
 * @version <tt>$Revision$</tt>
 *
 */
public class DestinationObjectFactoryTest extends UnitTestCase
{
   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   public void testReference() throws Exception
   {
      HornetQDestination queue = new HornetQQueue(randomString());
      Reference reference = queue.getReference();

      DestinationObjectFactory factory = new DestinationObjectFactory();
      Object object = factory.getObjectInstance(reference, null, null, null);
      assertNotNull(object);
      assertTrue(object instanceof HornetQDestination);
      assertEquals(queue, object);
   }
   
   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------
}