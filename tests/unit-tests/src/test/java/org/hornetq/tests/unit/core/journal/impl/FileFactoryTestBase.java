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

package org.hornetq.tests.unit.core.journal.impl;

import java.nio.ByteBuffer;

import junit.framework.Assert;

import org.hornetq.core.journal.SequentialFile;
import org.hornetq.core.journal.SequentialFileFactory;
import org.hornetq.tests.util.UnitTestCase;

/**
 * 
 * @author clebert.suconic@jboss.org
 *
 */
public abstract class FileFactoryTestBase extends UnitTestCase
{
   protected abstract SequentialFileFactory createFactory();

   protected SequentialFileFactory factory;

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();

      factory = createFactory();
   }

   // Protected ---------------------------------

   protected void checkFill(final SequentialFile file, final int pos, final int size, final byte fillChar) throws Exception
   {
      file.fill(pos, size, fillChar);

      file.close();

      file.open();

      file.position(pos);

      ByteBuffer bb = ByteBuffer.allocateDirect(size);

      int bytesRead = file.read(bb);

      Assert.assertEquals(size, bytesRead);

      bb.rewind();

      byte bytes[] = new byte[size];

      bb.get(bytes);

      for (int i = 0; i < size; i++)
      {
         // log.debug(" i is " + i);
         Assert.assertEquals(fillChar, bytes[i]);
      }

   }

}
