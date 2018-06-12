package org.ergoplatform

import akka.actor.{Actor, ActorRef, Props, Terminated}

import scala.util.{Failure, Success}

object BlockChainNodeActor {
  def props(startBlockChain: BlockChain, knownPeers: Seq[ActorRef]): Props = Props(new BlockChainNodeActor(startBlockChain, knownPeers))

  case object GetBlockChain

  case object GetConnectedPeers

  case class ConnectTo(peer: ActorRef)

  case object Ack

  case class Score(score: Long)

}

class BlockChainNodeActor(startBlockChain: BlockChain, knownPeers: Seq[ActorRef]) extends Actor {

  import BlockChainNodeActor._

  override def preStart(): Unit = {
    context.become(active(startBlockChain, knownPeers))
    knownPeers.foreach(ref => {
      ref ! ConnectTo(self)
      ref ! Score(startBlockChain.score)
    })
  }

  private def active(blockChain: BlockChain, peers: Seq[ActorRef]): Receive = {
    case GetConnectedPeers =>
      sender() ! peers
    case GetBlockChain =>
      sender() ! blockChain
    case ConnectTo(node) =>
      context.watch(node)
      context.become(active(blockChain, peers :+ node))
      node ! Ack
      node ! Score(blockChain.score)
    case Ack =>
      val node = sender()
      context.watch(node)
      context.become(active(blockChain, peers :+ node))
    case Terminated(terminatedNode) =>
      context.become(active(blockChain, peers.filter(_ != terminatedNode)))
    case b: Block => blockChain.append(b) match {
      case Success(newBlockChain) =>
        context.become(active(newBlockChain, peers))
        peers.foreach(_ ! Score(newBlockChain.score))
      case Failure(f) =>
        println(s"Error on apply block $b to blockchain $blockChain: $f")
    }
    case s @ Score(score) =>
      val tx = sender()
      if (score > blockChain.score) {
        tx ! GetBlockChain
        peers.filterNot(_ == tx).foreach(_.tell(s, tx))
      } else if (score < blockChain.score) tx ! Score(blockChain.score)
    case bc: BlockChain =>
      val tx = sender()
      if (bc.score > blockChain.score) context.become(active(bc, peers))
  }

  override def receive = Actor.emptyBehavior
}
