package org.bitcoins.spvnode.block

import org.bitcoins.core.consensus.Merkle
import org.bitcoins.core.crypto.DoubleSha256Digest
import org.bitcoins.core.number.{UInt32, UInt64}
import org.bitcoins.core.protocol.CompactSizeUInt
import org.bitcoins.core.protocol.blockchain.Block
import org.bitcoins.core.util._

import scala.annotation.tailrec
import scala.math._

/**
  * Created by chris on 8/7/16.
  * Represents a subset of known txids inside of a [[Block]]
  * in a way that allows recovery of the txids & merkle root
  * without having to store them all explicitly.
  *
  * Encoding procedure:
  * Traverse the tree in depth first order, storing a bit for each traversal.
  * This bit signifies if the node is a parent of at least one
  * matched leaf txid (or a matched leaf txid) itself.
  * In case we are the leaf level, or this bit is 0, it's merkle
  * node hash is stored and it's children are not explored any further.
  * Otherwise no hash is stored, but we recurse all of this node's child branches.
  *
  * Decoding procedure:
  * The same depth first decoding procedure is performed, but we consume the
  * bits and hashes that we used during encoding
  *
  */
trait PartialMerkleTree extends BitcoinSLogger {

  /** The total number of transactions in this block */
  def transactionCount: UInt32

  /** The actual scala integer representation for [[transactionCount]] */
  private def numTransactions: Int = transactionCount.toInt

  /** Maximum height of the [[tree]] */
  private def maxHeight = PartialMerkleTree.calcMaxHeight(numTransactions)

  /** The actual tree used to represent this partial merkle tree*/
  def tree: BinaryTree[DoubleSha256Digest]

  /** A sequence representing if this node is the parent of another node that matched a txid */
  def bits: Seq[Boolean]

  /** Extracts the txids that were matched inside of the bloom filter used to create this partial merkle tree */
  def extractMatches: Seq[DoubleSha256Digest] = {
    //TODO: This is some really ugly that isn't tail recursive, try to clean this up eventually
    logger.debug("Starting bits for extraction: " + bits)
    logger.debug("Starting tree: " + tree)
    def loop(subTree: BinaryTree[DoubleSha256Digest],
             remainingBits: Seq[Boolean], height: Int, accumMatches: Seq[DoubleSha256Digest]): (Seq[DoubleSha256Digest], Seq[Boolean]) = {
      if (height == maxHeight) {
        if (remainingBits.head) {
          //means we have a txid node that matched the filter
          subTree match {
            case l : Leaf[DoubleSha256Digest] =>
              logger.debug("Adding " + l.v + " to matches")
              logger.debug("Remaining bits: " + remainingBits.tail)
              (l.v +: accumMatches, remainingBits.tail)
            case n : Node[DoubleSha256Digest] =>
              //means we have a txid node as the root of the merkle tree
              val (leftTreeMatches,leftRemainingBits) = loop(n.l,remainingBits.tail,height+1,accumMatches)
              val (rightTreeMatches,rightRemainingBits) = loop(n.r,leftRemainingBits, height+1, leftTreeMatches)
              (n.v +: rightTreeMatches,rightRemainingBits)
            case Empty => throw new IllegalArgumentException("We cannot have a " +
              "Node or Empty node when we supposedly have a txid node -- txid nodes should always be leaves, got: " + Empty)
          }
        } else {
          //means we have a txid node, but it did not match the filter
          (accumMatches,remainingBits.tail)
        }
      } else {
        //means we have a nontxid node
        if (remainingBits.head) {
          //means we have a match underneath this node
          subTree match {
            case n: Node[DoubleSha256Digest] =>
              //since we are just trying to extract bloom filter matches, recurse into the two subtrees
              val (leftTreeMatches,leftRemainingBits) = loop(n.l,remainingBits.tail,height+1,accumMatches)
              val (rightTreeMatches,rightRemainingBits) = loop(n.r,leftRemainingBits, height+1, leftTreeMatches)
              (rightTreeMatches,rightRemainingBits)
            case l : Leaf[DoubleSha256Digest] =>
              (accumMatches, remainingBits.tail)
            case Empty => throw new IllegalArgumentException("We cannot have an empty node when we supposedly have a match underneath this node since it has no children")
          }
        } else {
          (accumMatches, remainingBits.tail)
        }
      }
    }
    val (matches,remainingBits) = loop(tree,bits,0,Nil)
    require(remainingBits.isEmpty,"We cannot have any left over bits after traversing the tree, got: " + remainingBits)
    matches.reverse
  }

  /** The hashes used to create the binary tree */
  def hashes: Seq[DoubleSha256Digest]
}


object PartialMerkleTree extends BitcoinSLogger {

  private case class PartialMerkleTreeImpl(tree: BinaryTree[DoubleSha256Digest], bits: Seq[Boolean],
                                           transactionCount: UInt32, hashes: Seq[DoubleSha256Digest]) extends PartialMerkleTree

  def apply(txMatches: Seq[(Boolean,DoubleSha256Digest)]): PartialMerkleTree = {
    val txIds = txMatches.map(_._2)
    val merkleTree: Merkle.MerkleTree = Merkle.build(txIds)
    val (bits,hashes) = build(merkleTree,txMatches)
    val tree = reconstruct(txIds.size,hashes,bits)
    PartialMerkleTreeImpl(tree,bits,UInt32(txIds.size),hashes)
  }


  /**
    *
    * @param fullMerkleTree the full merkle tree which we are going to trim to get a partial merkle tree
    * @param txMatches indicates whether the given txid matches the bloom filter, the full merkle branch needs
    *                  to be included inside of the [[PartialMerkleTree]]
    * @return the binary tree that represents the partial merkle tree, the bits needed to reconstruct this partial merkle tree, and the hashes needed to be inserted
    *         according to the flags inside of bits
    */
  def build(fullMerkleTree: Merkle.MerkleTree, txMatches: Seq[(Boolean,DoubleSha256Digest)]): (Seq[Boolean], Seq[DoubleSha256Digest]) = {
    val maxHeight = calcMaxHeight(txMatches.size)
    logger.debug("Tx matches: " + txMatches)
    logger.debug("Tx matches size: " + txMatches.size)
    logger.debug("max height: "+ maxHeight)

    /**
      * This loops through our merkle tree building [[bits]] so we can instruct another node how to create the partial merkle tree
      * @param bits the accumulator for bits indicating how to reconsctruct the partial merkle tree
      * @param hashes the relevant hashes used with bits to reconstruct the merkle tree
      * @param height the transaction index we are currently looking at -- if it was matched in our bloom filter we need the entire merkle branch
      * @return the binary tree that represents the partial merkle tree, the bits needed to reconstruct this partial merkle tree, and the hashes needed to be inserted
      *         according to the flags inside of bits
      */
    def loop(bits: Seq[Boolean], hashes: Seq[DoubleSha256Digest], height: Int, pos: Int): (Seq[Boolean], Seq[DoubleSha256Digest]) = {
      val parentOfMatch = matchesTx(maxHeight,height, pos, txMatches)
      logger.debug("parent of match: " + parentOfMatch)
      val newBits = parentOfMatch +: bits
      if (height == 0 || !parentOfMatch) {
        //means that we are either at the root of the merkle tree or there is nothing interesting below
        //this node in our binary tree
        val nodeHash = calcHash(height,pos,txMatches.map(_._2))
        val newHashes = nodeHash +: hashes
        (newBits,newHashes)
      } else {
        //process the left node
        val (leftBits,leftHashes) = loop(newBits, hashes, height-1, pos*2)
        if ((pos*2) + 1 < calcTreeWidth(txMatches.size, height-1)) {
          //process the right node if the tree's width is larger than the position we are looking at
          loop(leftBits,leftHashes, height-1, (pos*2) + 1)
        } else (leftBits,leftHashes)
      }
    }
    val txIds = txMatches.map(_._2)
    val (bits,hashes) = loop(Nil, Nil, maxHeight,0)
    (bits.reverse,hashes.reverse)
  }


  /** Checks if a node at given the given height and position matches a transaction in the sequence */
  def matchesTx(maxHeight: Int, height: Int, pos: Int, matchedTx: Seq[(Boolean,DoubleSha256Digest)]): Boolean = {
    //mimics this functionality inside of bitcoin core
    //https://github.com/bitcoin/bitcoin/blob/master/src/merkleblock.cpp#L83-L84
    @tailrec
    def loop(p: Int): Boolean = {
      if ((p < ((pos + 1) << height)) && p < matchedTx.size) {
        if (matchedTx(p)._1) return true
        else loop(p + 1)
      } else false
    }
    val startingP = pos << height
    logger.debug("Height: " + height + " pos: " + pos + " startingP: " + startingP)
    loop(startingP)
  }

  /** Simple way to calculate the maximum width of a binary tree */
  private def calcTreeWidth(numTransactions: Int, height: Int) = (numTransactions+ (1 << height)-1) >> height

  /** Calculates the hash of a node in the merkle tree */
  private def calcHash(height : Int, pos : Int, txIds: Seq[DoubleSha256Digest]): DoubleSha256Digest = {
    //follows this function inside of bitcoin core
    //https://github.com/bitcoin/bitcoin/blob/master/src/merkleblock.cpp#L63
    if (height == 0) txIds(pos)
    else {
      val leftHash =  calcHash(height-1,pos * 2, txIds)
      val rightHash = if ((pos * 2) + 1 < calcTreeWidth(txIds.size, height-1)) {
        calcHash(height-1, (pos * 2) + 1, txIds)
      } else leftHash
      CryptoUtil.doubleSHA256(leftHash.bytes ++ rightHash.bytes)
    }
  }

  /**
    * Function to reconstruct a partial merkle tree
    * @param transactionCount the number of transactions inside of the partial merkle tree
    * @param hashes the hashes used to reconstruct the partial merkle tree
    * @param bits the bits used indicate the structure of the partial merkle tree
    * @return
    */
  def apply(transactionCount: UInt32, hashes: Seq[DoubleSha256Digest], bits: Seq[Boolean]): PartialMerkleTree = {
    val tree = reconstruct(transactionCount.toInt,hashes,bits)
    PartialMerkleTreeImpl(tree,bits,transactionCount,hashes)
  }


  /**
    * This constructor creates a partial from this given [[BinaryTree]]
    * You probably don't want to use this constructor, unless you manually constructed [[bits]] and the [[tree]]
    * by hand
    * @param tree the partial merkle tree -- note this is NOT the full merkle tree
    * @param bits the path to the matches in the partial merkle tree
    * @param numTransactions the number of transactions there initially was in the full merkle tree
    * @param hashes the hashes used to reconstruct the binary tree according to [[bits]]
    * @return
    */
  def apply(tree: BinaryTree[DoubleSha256Digest], bits: Seq[Boolean], numTransactions: Int, hashes: Seq[DoubleSha256Digest]): PartialMerkleTree = {
    PartialMerkleTreeImpl(tree,bits, UInt32(numTransactions), hashes)
  }

  /** Builds a partial merkle tree the information inside of a [[org.bitcoins.spvnode.messages.MerkleBlockMessage]]
    * [[https://bitcoin.org/en/developer-reference#parsing-a-merkleblock-message]]
    *
    * @param numTransaction
    * @param hashes
    * @param matches
    * @return
    */
  def reconstruct(numTransaction: Int, hashes: Seq[DoubleSha256Digest], matches: Seq[Boolean]): BinaryTree[DoubleSha256Digest] = {
    val maxHeight = calcMaxHeight(numTransaction)
    //TODO: Optimize to tailrec function
    def loop(remainingHashes: Seq[DoubleSha256Digest], remainingMatches: Seq[Boolean], height: Int) : (BinaryTree[DoubleSha256Digest],Seq[DoubleSha256Digest], Seq[Boolean]) = {
      logger.debug("Remaining hashes: " + remainingHashes)
      logger.debug("Remaining matches: " + remainingMatches)
      logger.debug("Height: " + height)
      if (height == maxHeight) {
        //means we have a txid node
        (Leaf(remainingHashes.head),
          remainingHashes.tail,
          remainingMatches.tail)
      } else {
        //means we have a non txid node
        if (remainingMatches.head) {
          val (leftNode,leftRemainingHashes,leftRemainingBits) = loop(remainingHashes,remainingMatches.tail,height+1)
          val (rightNode,rightRemainingHashes, rightRemainingBits) = loop(leftRemainingHashes,leftRemainingBits,height+1)
          require(leftNode.value.get != rightNode.value.get, "Cannot have the same hashes in two child nodes, got: " + leftNode + " and " + rightNode)
          val nodeHash = CryptoUtil.doubleSHA256(leftNode.value.get.bytes ++ rightNode.value.get.bytes)
          val node = Node(nodeHash,leftNode,rightNode)
          (node,rightRemainingHashes,rightRemainingBits)
        } else (Leaf(remainingHashes.head),remainingHashes.tail,remainingMatches.tail)
      }
    }
    logger.info("Max height: " + maxHeight)
    logger.info("Original hashes: " + hashes)
    logger.info("Original matches: " + matches)
    val (tree,remainingHashes,remainingMatches) = loop(hashes,matches,0)
    require(remainingHashes.size == 0,"We should not have any left over hashes after building our partial merkle tree, got: " + remainingHashes )
    //we must not have any matches remaining, unless the remaining bits were use to pad our byte vector to 8 bits
    //for instance, we could have had 5 bits to indicate how to build the merkle tree, but we need to pad it with 3 bits
    //to give us a full byte to serialize and send over the network
    logger.info("hashes.size + remainingMatches.size: " + (hashes.size + remainingMatches.size))
    //require(((hashes.size + remainingMatches.size) / 8) == 0, "We should not have any remaining matches except for those that pad our byte after building our partial merkle tree, got: " + remainingMatches)
    tree
  }

  /** Calculates the maximum height for a binary tree with the number of transactions specified */
  def calcMaxHeight(numTransactions: Int): Int = Math.ceil((log(numTransactions) / log(2))).toInt

}
