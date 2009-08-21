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
package org.hornetq.core.example;

import java.util.Date;

import org.hornetq.core.client.ClientConsumer;
import org.hornetq.core.client.ClientMessage;
import org.hornetq.core.client.ClientProducer;
import org.hornetq.core.client.ClientSession;
import org.hornetq.core.client.ClientSessionFactory;
import org.hornetq.core.client.impl.ClientSessionFactoryImpl;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.config.TransportConfiguration;
import org.hornetq.core.config.impl.ConfigurationImpl;
import org.hornetq.core.remoting.impl.invm.InVMAcceptorFactory;
import org.hornetq.core.remoting.impl.invm.InVMConnectorFactory;
import org.hornetq.core.server.HornetQ;
import org.hornetq.core.server.HornetQServer;

/**
 * 
 * This exammple shows how to run a HornetQ core client and server embedded in your
 * own application
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 *
 */
public class EmbeddedExample
{

   public static void main(String[] args)
   {
      try
      {
         
         // Step 1. Create the Configuration, and set the properties accordingly
         Configuration configuration = new ConfigurationImpl();
         configuration.setPersistenceEnabled(false);
         configuration.setSecurityEnabled(false);
         configuration.getAcceptorConfigurations().add(new TransportConfiguration(InVMAcceptorFactory.class.getName()));
         
         // Step 2. Create and start the server
         HornetQServer server = HornetQ.newHornetQServer(configuration);
         server.start();
   
   
         // Step 3. As we are not using a JNDI environment we instantiate the objects directly         
         ClientSessionFactory sf = new ClientSessionFactoryImpl (new TransportConfiguration(InVMConnectorFactory.class.getName()));
         
         // Step 4. Create a core queue
         ClientSession coreSession = sf.createSession(false, false, false);
         
         final String queueName = "queue.exampleQueue";
         
         coreSession.createQueue(queueName, queueName, true);
         
         coreSession.close();
                  
         ClientSession session = null;
   
         try
         {
   
            // Step 5. Create the session, and producer
            session = sf.createSession();
                                   
            ClientProducer producer = session.createProducer(queueName);
   
            // Step 6. Create and send a message
            ClientMessage message = session.createClientMessage(false);
            
            final String propName = "myprop";
            
            message.putStringProperty(propName, "Hello sent at " + new Date());
            
            System.out.println("Sending the message.");
            
            producer.send(message);

            // Step 7. Create the message consumer and start the connection
            ClientConsumer messageConsumer = session.createConsumer(queueName);
            session.start();
   
            // Step 8. Receive the message. 
            ClientMessage messageReceived = messageConsumer.receive(1000);
            System.out.println("Received TextMessage:" + messageReceived.getProperty(propName));
         }
         finally
         {
            // Step 9. Be sure to close our resources!
            if (session != null)
            {
               session.close();
            }
            
            // Step 10. Stop the server
            server.stop();
         }
      }
      catch (Exception e)
      {
         e.printStackTrace();
         System.exit(-1);
      }
   }

}