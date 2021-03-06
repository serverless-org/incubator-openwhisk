/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.loadBalancer.test

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future

import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import akka.actor.FSM
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.pattern.ask
import akka.testkit.ImplicitSender
import akka.testkit.TestFSMRef
import akka.testkit.TestKit
import akka.testkit.TestProbe
import akka.util.Timeout
import common.StreamLogging
import whisk.common.ConsulKV.LoadBalancerKeys
import whisk.common.KeyValueStore
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.connector.ActivationMessage
import whisk.core.connector.MessageConsumer
import whisk.core.connector.PingMessage
import whisk.core.entitlement.Privilege.Privilege
import whisk.core.entity.ActivationId.ActivationIdGenerator
import whisk.core.entity.AuthKey
import whisk.core.entity.DocRevision
import whisk.core.entity.EntityName
import whisk.core.entity.EntityPath
import whisk.core.entity.ExecManifest
import whisk.core.entity.FullyQualifiedEntityName
import whisk.core.entity.Identity
import whisk.core.entity.Secret
import whisk.core.entity.Subject
import whisk.core.entity.UUID
import whisk.core.loadBalancer.ActivationRequest
import whisk.core.loadBalancer.GetStatus
import whisk.core.loadBalancer.Healthy
import whisk.core.loadBalancer.InvocationFinishedMessage
import whisk.core.loadBalancer.InvokerActor
import whisk.core.loadBalancer.InvokerPool
import whisk.core.loadBalancer.InvokerState
import whisk.core.loadBalancer.Offline
import whisk.core.loadBalancer.UnHealthy
import whisk.utils.retry

@RunWith(classOf[JUnitRunner])
class InvokerSupervisionTests extends TestKit(ActorSystem("InvokerSupervision"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with MockFactory
    with StreamLogging {

    val config = new WhiskConfig(ExecManifest.requiredProperties)

    ExecManifest.initialize(config)

    override def afterAll {
        TestKit.shutdownActorSystem(system)
    }

    implicit val timeout = Timeout(5.seconds)

    /** Imitates a StateTimeout in the FSM */
    def timeout(actor: ActorRef) = actor ! FSM.StateTimeout

    /** Queries all invokers for their state */
    def allStates(pool: ActorRef) = Await.result(pool.ask(GetStatus).mapTo[Map[String, InvokerState]], timeout.duration)

    behavior of "InvokerPool"

    it should "successfully create invokers in its pool on ping and keep track of statechanges" in {
        val invoker0 = TestProbe()
        val invoker1 = TestProbe()
        val invoker0Name = invoker0.ref.path.name
        val invoker1Name = invoker1.ref.path.name

        val children = mutable.Queue(invoker0.ref, invoker1.ref)
        val childFactory = (f: ActorRefFactory, name: String) => children.dequeue()

        val kv = stub[KeyValueStore]
        val sendActivationToInvoker = stubFunction[ActivationMessage, String, Future[RecordMetadata]]
        val pC = stub[MessageConsumer]
        val supervisor = system.actorOf(InvokerPool.props(childFactory, kv, () => _, sendActivationToInvoker, pC))

        within(timeout.duration) {
            // create first invoker
            val ping0 = PingMessage(invoker0Name)
            supervisor ! ping0
            invoker0.expectMsgType[SubscribeTransitionCallBack] // subscribe to the actor
            invoker0.expectMsg(ping0)

            invoker0.send(supervisor, CurrentState(invoker0.ref, Healthy))
            allStates(supervisor) shouldBe Map(invoker0Name -> Healthy)

            // create second invoker
            val ping1 = PingMessage(invoker1Name)
            supervisor ! ping1
            invoker1.expectMsgType[SubscribeTransitionCallBack]
            invoker1.expectMsg(ping1)

            invoker1.send(supervisor, CurrentState(invoker1.ref, Healthy))
            allStates(supervisor) shouldBe Map(invoker0Name -> Healthy, invoker1Name -> Healthy)

            // ping the first invoker again
            supervisor ! ping0
            invoker0.expectMsg(ping0)

            allStates(supervisor) shouldBe Map(invoker0Name -> Healthy, invoker1Name -> Healthy)

            // one invoker goes offline
            invoker1.send(supervisor, Transition(invoker1.ref, Healthy, Offline))
            allStates(supervisor) shouldBe Map(invoker0Name -> Healthy, invoker1Name -> Offline)
        }
    }

    it should "publish state changes via kv and call the provided callback if an invoker goes offline" in {
        val invoker = TestProbe()
        val invokerName = invoker.ref.path.name
        val childFactory = (f: ActorRefFactory, name: String) => invoker.ref

        val kv = stub[KeyValueStore]
        val callback = stubFunction[String, Unit]
        val sendActivationToInvoker = stubFunction[ActivationMessage, String, Future[RecordMetadata]]
        val pC = stub[MessageConsumer]
        val supervisor = system.actorOf(InvokerPool.props(childFactory, kv, callback, sendActivationToInvoker, pC))

        within(timeout.duration) {
            // create first invoker
            val ping0 = PingMessage(invokerName)
            supervisor ! ping0
            invoker.expectMsgType[SubscribeTransitionCallBack] // subscribe to the actor
            invoker.expectMsg(ping0)

            // triggers kv.put
            invoker.send(supervisor, CurrentState(invoker.ref, Healthy))
            // triggers kv.put and callback
            invoker.send(supervisor, Transition(invoker.ref, Healthy, Offline))
            // triggers another kv.put
            invoker.send(supervisor, Transition(invoker.ref, Offline, Healthy))
        }

        retry({
            (kv.put _).verify(LoadBalancerKeys.invokerHealth, *).repeated(3)
            callback.verify(invokerName)
        }, N = 3, waitBeforeRetry = Some(500.milliseconds))
    }

    it should "forward the ActivationResult to the appropriate invoker" in {
        val invoker = TestProbe()
        val invokerName = invoker.ref.path.name
        val childFactory = (f: ActorRefFactory, name: String) => invoker.ref
        val kv = stub[KeyValueStore]
        val sendActivationToInvoker = stubFunction[ActivationMessage, String, Future[RecordMetadata]]
        val pC = stub[MessageConsumer]

        val supervisor = system.actorOf(InvokerPool.props(childFactory, kv, () => _, sendActivationToInvoker, pC))

        within(timeout.duration) {
            // Create one invoker
            val ping0 = PingMessage(invokerName)
            supervisor ! ping0
            invoker.expectMsgType[SubscribeTransitionCallBack] // subscribe to the actor
            invoker.expectMsg(ping0)
            invoker.send(supervisor, CurrentState(invoker.ref, Healthy))
            allStates(supervisor) shouldBe Map(invokerName -> Healthy)

            // Send message and expect receive in invoker
            val msg = InvocationFinishedMessage(invokerName, true)
            supervisor ! msg
            invoker.expectMsg(msg)
        }
    }

    it should "forward an ActivationMessage to the sendActivation-Method" in {
        val invoker = TestProbe()
        val invokerName = invoker.ref.path.name
        val childFactory = (f: ActorRefFactory, name: String) => invoker.ref

        val kv = stub[KeyValueStore]
        val sendActivationToInvoker = stubFunction[ActivationMessage, String, Future[RecordMetadata]]
        val pC = stub[MessageConsumer]

        val supervisor = system.actorOf(InvokerPool.props(childFactory, kv, () => _, sendActivationToInvoker, pC))

        // Send ActivationMessage to InvokerPool
        val activationMessage = ActivationMessage(
            transid = TransactionId.invokerHealth,
            action = FullyQualifiedEntityName(EntityPath("whisk.system/utils"), EntityName("date")),
            revision = DocRevision.empty,
            user = Identity(Subject("unhealthyInvokerCheck"), EntityName("unhealthyInvokerCheck"), AuthKey(UUID(), Secret()), Set[Privilege]()),
            activationId = new ActivationIdGenerator {}.make(),
            activationNamespace = EntityPath("guest"),
            content = None)
        val msg = ActivationRequest(activationMessage, invokerName)

        sendActivationToInvoker.when(activationMessage, invokerName).returns(Future.successful(new RecordMetadata(new TopicPartition(invokerName, 0), 0L, 0L, 0L, 0L, 0, 0)))

        supervisor ! msg

        // Verify, that MessageProducer will receive a call to send the message
        retry(sendActivationToInvoker.verify(activationMessage, invokerName).once, N = 3, waitBeforeRetry = Some(500.milliseconds))
    }

    behavior of "InvokerActor"

    // unHealthy -> offline
    // offline -> unhealthy
    it should "start unhealthy, go offline if the state times out and goes unhealthy on a successful ping again" in {
        val pool = TestProbe()
        val invoker = pool.system.actorOf(InvokerActor.props)

        within(timeout.duration) {
            pool.send(invoker, SubscribeTransitionCallBack(pool.ref))
            pool.expectMsg(CurrentState(invoker, UnHealthy))
            timeout(invoker)
            pool.expectMsg(Transition(invoker, UnHealthy, Offline))

            invoker ! PingMessage("testinvoker")
            pool.expectMsg(Transition(invoker, Offline, UnHealthy))
        }
    }

    // unhealthy -> healthy
    it should "goto healthy again, if unhealthy and error buffer has enough successful invocations" in {
        val pool = TestProbe()
        val invoker = pool.system.actorOf(InvokerActor.props)

        within(timeout.duration) {
            pool.send(invoker, SubscribeTransitionCallBack(pool.ref))
            pool.expectMsg(CurrentState(invoker, UnHealthy))

            // Fill buffer with errors
            (1 to InvokerActor.bufferSize).foreach { _ =>
                invoker ! InvocationFinishedMessage("testinvoker", false)
            }

            // Fill buffer with successful invocations to become healthy again (one below errorTolerance)
            (1 to InvokerActor.bufferSize - InvokerActor.bufferErrorTolerance).foreach { _ =>
                invoker ! InvocationFinishedMessage("testinvoker", true)
            }
            pool.expectMsg(Transition(invoker, UnHealthy, Healthy))
        }
    }

    // unhealthy -> offline
    // offline -> unhealthy
    it should "go offline when unhealthy, if the state times out and go unhealthy on a successful ping again" in {
        val pool = TestProbe()
        val invoker = pool.system.actorOf(InvokerActor.props)

        within(timeout.duration) {
            pool.send(invoker, SubscribeTransitionCallBack(pool.ref))
            pool.expectMsg(CurrentState(invoker, UnHealthy))

            timeout(invoker)
            pool.expectMsg(Transition(invoker, UnHealthy, Offline))

            invoker ! PingMessage("testinvoker")
            pool.expectMsg(Transition(invoker, Offline, UnHealthy))
        }
    }

    it should "start timer to send testactions when unhealthy" in {
        val invoker = TestFSMRef(new InvokerActor)
        invoker.stateName shouldBe UnHealthy

        invoker.isTimerActive(InvokerActor.timerName) shouldBe true

        // Fill buffer with successful invocations to become healthy again (one below errorTolerance)
        (1 to InvokerActor.bufferSize - InvokerActor.bufferErrorTolerance).foreach { _ =>
            invoker ! InvocationFinishedMessage("testinvoker", true)
        }
        invoker.stateName shouldBe Healthy

        invoker.isTimerActive(InvokerActor.timerName) shouldBe false
    }
}
