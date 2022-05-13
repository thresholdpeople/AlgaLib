// AlgaLib: SuperCollider implementation of Alga, an interpolating live coding environment.
// Copyright (C) 2020-2022 Francesco Cameli.

// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.

AlgaBlock {
	//All the nodes in the block
	var <nodes;

	//All the nodes that already have been visited
	var <visitedNodes;

	//Used for disconnect
	var <disconnectVisitedNodes;

	//All the nodes that have FB connections
	var <feedbackNodes;

	//Used for disconnect
	var <atLeastOneFeedback = false;

	//OrderedIdentitySet of ordered nodes
	var <orderedNodes;

	//Array of IdentitySets of node dependencies
	var <groupedOrderedNodes;
	var <currentGroupSet;

	//the index for this block in the AlgaBlocksDict global dict
	var <blockIndex;

	//the Group
	var <group;

	//the ParGroups / Groups within the Group
	var <groups;

	//These are the top groups that belonged to merging AlgaBlocks
	var <isMergedGroup = false;
	var <mergedGroups;

	//Nodes at the top of the block
	var <upperMostNodes;

	//Used when all connections are FB
	var <lastSender;

	*new { | parGroup |
		^super.new.init(parGroup)
	}

	init { | parGroup |
		//Essential to be ordered to maintain when things were added to block!
		nodes          = OrderedIdentitySet(10);
		feedbackNodes  = IdentityDictionary(10);

		visitedNodes   = OrderedIdentitySet(10);
		disconnectVisitedNodes = IdentitySet(10);

		upperMostNodes = OrderedIdentitySet(10);
		orderedNodes   = OrderedIdentitySet(10);

		groupedOrderedNodes = Array.newClear;

		groups         = OrderedIdentitySet(10);
		group          = Group(parGroup, \addToHead);
		blockIndex     = group.nodeID;

		//Remove from blocks dict when group gets freed, eventually
		group.onFree({
			AlgaBlocksDict.blocksDict.removeAt(blockIndex)
		});
	}

	//Copy an AlgaBlock's nodes
	copyBlock { | senderBlock |
		if(senderBlock != nil, {
			senderBlock.nodes.do({ | node |
				this.addNode(node)
			});

			senderBlock.feedbackNodes.keysValuesDo({ | sender, receiver |
				this.addFeedback(sender, receiver)
			});

			//When merging blocks, the old one's core group must be freed!
			this.addMergedGroup(senderBlock);
			isMergedGroup = true;
		});
	}

	//Copy a node from a block to another and reset
	copyNodeAndReset { | node, senderBlock |
		if((senderBlock != nil).and(node != nil), {
			//Copy FBs
			senderBlock.feedbackNodes.keysValuesDo({ | sender, receiverSet |
				if(sender == node, {
					receiverSet.do({ | receiver |
						this.addFeedback(sender, receiver);
						senderBlock.removeFeedback(sender, receiver);
					});
				});
			});

			//Remove from orderedNodes
			senderBlock.orderedNodes.remove(node);

			//Remove from upperMostNodes
			senderBlock.upperMostNodes.remove(node);

			//Remove node: this sets blockIndex to -1 and removes FB
			senderBlock.removeNode(node);

			//Add node to this block: this sets blockIndex to the new one
			this.addNode(node);
		});
	}

	//Add group to mergedGroups for deletion. Also add the mergedGroups that that one had.
	addMergedGroup { | senderBlock |
		mergedGroups = mergedGroups ? IdentitySet();
		mergedGroups.add(senderBlock.group);
		senderBlock.mergedGroups.do({ | oldMergedGroup |
			mergedGroups.add(oldMergedGroup)
		});
	}

	//Add node to the block
	addNode { | node |
		//Unpack AlgaArg
		if(node.isAlgaArg, {
			node = node.sender;
			if(node.isAlgaNode.not, { ^nil });
		});

		//Debug
		if(Alga.debug, {
			("Adding node: " ++ node.asString ++ " to block: " ++ blockIndex).warn
		});

		//Add to IdentitySet
		nodes.add(node);

		//Check mismatch
		if(node.blockIndex != blockIndex, {
			//("blockIndex mismatch detected. Using " ++ blockIndex).warn;
			node.blockIndex = blockIndex;
		});
	}

	//Remove all FB related to the node
	removeFeedbacks { | node |
		feedbackNodes.removeAt(node);
		feedbackNodes.keysValuesDo({ | sender, receiverSet |
			receiverSet.remove(node);
			if(receiverSet.size == 0, { feedbackNodes.removeAt(sender) })
		});
	}

	//Is connection a feedback one?
	isFeedback { | sender, receiver |
		if(feedbackNodes[sender] != nil, {
			^feedbackNodes[sender].includes(receiver)
		})
		^false
	}

	//Remove a node from the block. If the block is empty, free its group
	removeNode { | node, removeFB = true |
		if(node.blockIndex != blockIndex, {
			"Trying to remove a node from a block that did not contain it!".warn;
			^nil;
		});

		//Debug
		if(Alga.debug, {
			("Removing node: " ++ node.asString ++ " from block: " ++ blockIndex).warn
		});

		//Remove from dict
		nodes.remove(node);

		//Remove from FB connections
		if(removeFB, { this.removeFeedbacks(node) });

		//Set node's index to -1
		node.blockIndex = -1;

		//Put node back to top parGroup
		node.moveToHead(Alga.parGroup(node.server));

		//Remove this block from AlgaBlocksDict if it's empty!
		if(nodes.size == 0, {
			//("Deleting empty block: " ++ blockIndex).warn;
			AlgaBlocksDict.blocksDict.removeAt(blockIndex);

			//Make sure all nodes were removed! This is essential
			fork { 1.wait; group.free; };
		});
	}

	//Debug the FB connections
	debugFeedbacks {
		feedbackNodes.keysValuesDo({ | sender, receiversSet |
			receiversSet.do({ | receiver |
				("FB: " ++ sender.asString ++ " >> " ++ receiver.asString).warn;
			});
		});
	}

	//Debug orderedNodes
	debugOrderedNodes {
		"".postln;
		"OrderedNodes:".warn;
		orderedNodes.do({ | node |
			node.asString.warn;
		});
	}

	//Debug groupedOrderedNodes
	debugGroupedOrderedNodes {
		"".postln;
		"GroupedOrderedNodes:".warn;
		groupedOrderedNodes.do({ | set, i |
			("Group " ++ (i+1).asString ++ ":").warn;
			set.do({ | node |
				node.asString.warn;
			});
			"".postln;
		});
	}

	/***********/
	/* CONNECT */
	/***********/

	//Re-arrange block on connection
	rearrangeBlock { | sender, receiver |
		if(Alga.disableNodeOrdering.not, {
			var server, supernova;
			var ignoreStages;
			var newConnection = false;

			//Update lastSender
			lastSender = sender ? lastSender;

			//Stage 1: detect feedbacks between sender and receiver (if they're valid)
			if((sender != nil).and(receiver != nil), {
				this.stage1(sender, receiver);
				newConnection = true;
			});

			//Find unused feedback loops (from previous disconnections)
			this.findAllUnusedFeedbacks;

			//Find upper most nodes. This is done out of stage1 as it must always be executed,
			//while stage1 might not be (sender == nil and receiver == nil). At the same time,
			//this needs to happen before the other stages.
			this.findUpperMostNodes;

			//Ignore the successive stages IF
			//NOT a new connection AND
			//upperMostNodes is 0 (full FB block)
			ignoreStages = (newConnection.not).and(upperMostNodes.size == 0);
			if(ignoreStages.not, {
				//Stage 2: order nodes according to I/O
				this.stage2;

				//Check lastSender's server (it's been updated if sender != nil in stage1)
				server = if(lastSender != nil, { lastSender.server }, { Server.default });

				//Check if it's supernova
				supernova = Alga.supernova(server);

				//Stages 3-4
				if(supernova, {
					//Stage 3: optimize the ordered nodes (make groups)
					this.stage3;

					//Build ParGroups / Groups out of the optimized ordered nodes
					this.stage4_supernova;
				}, {
					//No stage3 (no need to parallelize order):
					//simply add orderedNods to group
					this.stage4_scsynth;
				});
			});

			//Debug space
			if(Alga.debug, { "".postln });
		});
	}

	/***********/
	/* STAGE 1 */
	/***********/

	//Stage 1: detect feedbacks
	stage1 { | sender, receiver |
		//Clear all needed stuff
		visitedNodes.clear;

		//Start to detect feedback from the receiver
		this.detectFeedback(
			node: receiver,
			blockSender: sender,
			blockReceiver: receiver
		);

		//Debug
		if(Alga.debug, { this.debugFeedbacks });
	}

	//Add FB pair
	addFeedback { | sender, receiver |
		//Create IdentitySets if needed
		if(feedbackNodes[sender] == nil, {
			feedbackNodes[sender] = IdentitySet();
		});

		/* if(feedbackNodes[receiver] == nil, {
			feedbackNodes[receiver] = IdentitySet();
		}); */

		//Add the FB connection
		feedbackNodes[sender].add(receiver);
		//feedbackNodes[receiver].add(sender);
	}

	//Remove FB pair
	removeFeedback { | sender, receiver |
		if(feedbackNodes[sender] != nil, {
			feedbackNodes[sender].remove(receiver);
			if(feedbackNodes[sender].size == 0, { feedbackNodes.removeAt(sender) });
		});

		/* if(feedbackNodes[receiver] != nil, {
			feedbackNodes[receiver].remove(sender);
			if(feedbackNodes[receiver].size == 0, { feedbackNodes.removeAt(receiver) });
		}); */
	}

	//Resolve feedback: check for the inNodes of the node.
	resolveFeedback { | node, nodeSender, blockSender, blockReceiver |
		//If there is a match between who sent the node (nodeSender)
		//and the original sender, AND between the current node and
		//the original receiver, it's feedback!
		if((nodeSender == blockSender).and(node == blockReceiver), {
			this.addFeedback(blockSender, blockReceiver);
			atLeastOneFeedback = true;
		});
	}

	//Detect feedback for a node
	detectFeedback { | node, nodeSender, blockSender, blockReceiver |
		//If node was already visited, its outNodes have already all been scanned.
		//This means that it can either be a feedback loop to be resolved, or an
		//already completed connection branch.
		var visited = visitedNodes.includes(node);
		if(visited, {
			^this.resolveFeedback(node, nodeSender, blockSender, blockReceiver);
		});

		//This node can be marked as visited
		visitedNodes.add(node);

		//Scan outNodes of this node
		node.activeOutNodes.keys.do({ | outNode |
			//nodeSender == node: the node who sent this outNode
			this.detectFeedback(outNode, node, blockSender, blockReceiver);
		});
	}

	/***********/
	/* STAGE 2 */
	/***********/

	//Stage 2: order nodes
	stage2 {
		//Clear all needed stuff
		visitedNodes.clear;
		orderedNodes.clear;

		//Order the nodes
		this.orderNodes;

		//Need to know upperMostNodes' size
		if(upperMostNodes.size > 0, {
			var visitedUpperMostNodes = Array.newClear(upperMostNodes.size);

			//Traverse branches from upperMostNodes
			this.traverseBranches(visitedUpperMostNodes);

			//Find blocks that should be separated
			this.findBlocksToSplit(visitedUpperMostNodes);
		});

		//Debug
		if(Alga.debug, { this.debugOrderedNodes });
	}

	//Create a new block
	createNewBlock { | server |
		var newBlock = AlgaBlock(Alga.parGroup(server));
		AlgaBlocksDict.blocksDict[newBlock.blockIndex] = newBlock;
		^newBlock;
	}

	//Create new block and order it
	createNewBlockAndOrderIt { | nodesSet |
		var firstNode, server, newBlock;
		block { | break |
			nodesSet.do({ | node |
				firstNode = node;
				server = node.server;
				break.value(nil)
			})
		};
		newBlock = this.createNewBlock(server);
		nodesSet.do({ | node | newBlock.copyNodeAndReset(node, this) });
		newBlock.rearrangeBlock(firstNode); //Have at least a sender for lastSender
	}

	//Find blocks that should be separated. Basically, this checks that
	//everytime a branch is computed from an upperMostNode, at least one of its visited nodes
	//must be in another branch, meaning they're linked. If a branch does not
	//have any node in common, it means it can be split into another block entirely.
	/*
	NOTE: this algorithm intentionally doesn't consider branches that are FB only, they're kept
	in this AlgaBlock until FB is removed so their order is ALWAYS guaranteed!
	In the future, these FB only branches could be detected and removed, but their new order
	must also be guaranteed.
	*/
	findBlocksToSplit { | visitedUpperMostNodes |
		if(visitedUpperMostNodes.size > 1, {
			visitedUpperMostNodes.do({ | branch1, i |
				//Only consider branches with more than one node
				if(branch1.size > 1, {
					var containsAnyOtherNode = false;
					block { | break |
						visitedUpperMostNodes.do({ | branch2 |
							if(branch1 != branch2, {
								if(branch1.sect(branch2).size > 0, {
									containsAnyOtherNode = true;
									break.value(nil);
								});
							});
						});
					};

					//Create new block and order it
					if(containsAnyOtherNode.not, {
						this.createNewBlockAndOrderIt(branch1);
					});
				});
			});
		});
	}

	//Check all nodes belonging to a branch
	traverseBranch { | node, visitedUpperMostNodesEntry, tempVisitedNodes |
		tempVisitedNodes.add(node);
		node.activeOutNodes.keys.do({ | receiver |
			var visited = tempVisitedNodes.includes(receiver);
			var isFeedback = this.isFeedback(node, receiver);
			if((visited.not).and(isFeedback.not), {
				this.traverseBranch(receiver, visitedUpperMostNodesEntry, tempVisitedNodes);
			});
		});
		visitedUpperMostNodesEntry.add(node);
	}

	//Check all nodes belonging to a branch
	traverseBranches {  | visitedUpperMostNodes |
		upperMostNodes.do({ | node, i |
			var tempVisitedNodes = IdentitySet();
			visitedUpperMostNodes[i] = IdentitySet();
			this.traverseBranch(node, visitedUpperMostNodes[i], tempVisitedNodes);
		});
	}

	//Find upper most nodes
	findUpperMostNodes {
		upperMostNodes.clear;
		nodes.do({ | node |
			if(node.activeInNodes.size == 0, {
				upperMostNodes.add(node);
			});
		});
	}

	//Order nodes according to their I/O
	orderNodes {
		//Bail after 5k tries
		var bailLimit = 5000;
		var bailCounter = 0;

		//Count all nodes down
		var nodeCounter = nodes.size;

		//Keep going 'til all nodes are done
		while { nodeCounter > 0 } {
			nodes.do({ | node |
				if(visitedNodes.includes(node).not, {
					var activeInNodesDone = true;
					var noIO = false;

					//If it has activeInNodesDone, check if all of them have
					//already been added. Also, ignore FB connections (their position is irrelevant)
					//FB's will maintain the same order thanks to nodes being OrderedIdentitySet
					if(node.activeInNodes.size > 0, {
						block { | break |
							node.activeInNodes.do({ | sendersSet |
								sendersSet.do({ | sender |
									//This can happen on CmdPeriod.
									//Make sure sender is in nodes or it will loop forever.
									if(nodes.includes(sender), {
										var visited = visitedNodes.includes(sender);
										var isFeedback = this.isFeedback(sender, node);
										var aboutToBail = bailCounter == (bailLimit - 1);

										//If about to bail, it means that the connection is actually
										//still present, but perhaps in between being changed.
										//It should be kept, and dealt with when new connections are introduced.
										//This is quit an edge case that should be handled better.
										if(aboutToBail, {
											activeInNodesDone = true;
											break.value(nil);
										});

										//Standard behaviour: if unvisited and not feedback, keep spinning
										if((visited.not).and(isFeedback.not), {
											activeInNodesDone = false;
											break.value(nil);
										});
									});
								});
							});
						};
					}, {
						//If also no outs, it's a node that has to be removed.
						//It will be done later in stage4 for all nodes that
						//haven't been added to orderedNodes
						if(node.activeOutNodes.size == 0, { noIO = true });
					});

					//If so, this node can be added
					if(activeInNodesDone, {
						visitedNodes.add(node);
						nodeCounter = nodeCounter - 1;
						if(noIO.not, { orderedNodes.add(node) });
					});
				});
			});

			bailCounter = bailCounter + 1;
			if(bailCounter == bailLimit, { nodeCounter = 0 });
		}
	}

	/***********/
	/* STAGE 3 */
	/***********/

	//Stage 3: optimize the ordered nodes (make groups)
	stage3 {
		//Clear all needed stuff
		visitedNodes.clear;
		groupedOrderedNodes = Array.newClear;
		currentGroupSet = IdentitySet();
		groupedOrderedNodes = groupedOrderedNodes.add(currentGroupSet);

		//Run optimizer
		this.optimizeOrderedNodes;

		//Debug
		if(Alga.debug, { this.debugGroupedOrderedNodes });
	}

	//Check if groupSet includes a sender of node
	groupSetIncludesASender { | groupSet, node |
		node.activeInNodes.do({ | sendersSet |
			sendersSet.do({ | sender |
				if(groupSet.includes(sender), { ^true });
			})
		})
		^false;
	}

	//Optimize a node
	optimizeNode { | node |
		var currentGroupSetIncludesASender = this.groupSetIncludesASender(currentGroupSet, node);

		//New group to create
		if(currentGroupSetIncludesASender, {
			var newGroupSet = IdentitySet();
			newGroupSet.add(node);
			groupedOrderedNodes = groupedOrderedNodes.add(newGroupSet);
			currentGroupSet = newGroupSet;
		}, {
			//Add to currentGroupSet
			currentGroupSet.add(node);
		});
	}

	//Optimize orderedNodes
	optimizeOrderedNodes {
		orderedNodes.do({ | node |
			this.optimizeNode(node);
		});
	}

	/***********/
	/* STAGE 4 */
	/***********/

	//Build Groups / ParGroups
	buildGroups {
		groupedOrderedNodes.do({ | groupSet |
			var newGroup;
			if(groupSet.size > 1, {
				newGroup = ParGroup(group, \addToTail);
			}, {
				newGroup = Group(group, \addToTail);
			});
			groupSet.do({ | node |
				node.moveToHead(newGroup);
				visitedNodes.add(node);
			});
			groups.add(newGroup);
		});
	}

	//Delete old groups and merged groups
	deleteOldGroups { | oldGroups |
		//If a group has just been merged, free those groups too
		if(isMergedGroup, {
			mergedGroups.do({ | mergedGroup | mergedGroup.free });
			mergedGroups.clear;
			isMergedGroup = false;
		});

		if(oldGroups != nil, {
			oldGroups.do({ | oldGroup | oldGroup.free })
		});
	}

	//Make a block with all the nodes that should be removed.
	removeUnvisitedNodes {
		var nodesToBeRemoved = OrderedIdentitySet();

		nodes.do({ | node |
			if(visitedNodes.includes(node).not, {
				nodesToBeRemoved.add(node);
			});
		});

		//At least 2 nodes, or it will loop forever!
		if((nodes.size != nodesToBeRemoved.size).and(
			nodesToBeRemoved.size > 1), {
			this.createNewBlockAndOrderIt(nodesToBeRemoved)
		}, {
			nodesToBeRemoved.do({ | node |
				this.removeNode(node)
			});
		});
	}

	//Stage 4: build ParGroups / Groups out of the optimized ordered nodes
	stage4_supernova {
		//Copy old groups
		var oldGroups = groups.copy;

		//Clear all needed stuff
		groups.clear;
		visitedNodes.clear;

		//Build new grups
		this.buildGroups;

		//Remove unvisited nodes.
		//This must come before deleteOldGroups, as nodes will be moved
		//back to top's ParGroup here.
		this.removeUnvisitedNodes;

		//Delete old groups (need to be locked due to fork)
		this.deleteOldGroups(oldGroups);
	}

	//Simply add orderedNodes to group
	addOrderedNodesToGroup {
		orderedNodes.do({ | node |
			node.moveToTail(group);
			visitedNodes.add(node);
		});
	}

	//Stage 4 scsynth: simply add orderedNodes to group
	stage4_scsynth {
		//Clear all needed stuff
		visitedNodes.clear;

		//Simply add orderedNodes to group
		this.addOrderedNodesToGroup;

		//In scsynth case, this is only needed to delete merged groups
		this.deleteOldGroups;

		//Remove unvisited nodes
		this.removeUnvisitedNodes;
	}

	/**************/
	/* DISCONNECT */
	/**************/

	//Re-arrange block on disconnect (needs WIP)
	rearrangeBlock_disconnect { | node |
		//Stage 1: free unused FB connections
		this.stage1_disconnect(node);

		//Debug
		if(Alga.debug, { this.debugFeedbacks });
	}

	/***********/
	/* STAGE 1 */
	/***********/

	//Free unused FB connections
	stage1_disconnect { | node |
		//Clear needed things
		disconnectVisitedNodes.clear;

		//Find unused FB connections from this node
		this.findUnusedFeedbacks(node);
	}

	//Find unused feedback loops related to node
	findUnusedFeedbacks { | node |
		disconnectVisitedNodes.add(node);
		node.activeOutNodes.keys.do({ | receiver |
			var visited = disconnectVisitedNodes.includes(receiver);

			//Found an old FB connection
			if(this.isFeedback(node, receiver), {
				//detectFeedback uses Class's visitedNodes and atLeastOneFeedback
				visitedNodes.clear;
				atLeastOneFeedback = false;

				//Run FB detection to see if at least one feedback is generated
				this.detectFeedback(
					node: receiver,
					blockSender: node,
					blockReceiver: receiver
				);

				//If no feedbacks, the pair can be removed
				if(atLeastOneFeedback.not, {
					this.removeFeedback(node, receiver);
				});
			});

			//Not visited, look through
			if(visited.not, {
				this.findUnusedFeedbacks(receiver);
			});
		});
	}

	//Running this on new connections?
	findAllUnusedFeedbacks {
		nodes.do({ | node |
			disconnectVisitedNodes.clear; //per-node
			this.findUnusedFeedbacks(node)
		});
	}
}

//Have a global one. No need to make one per server, as server identity is checked already.
AlgaBlocksDict {
	classvar <blocksDict;

	*initClass {
		var clearFunc = { AlgaBlocksDict.blocksDict.clear };
		blocksDict = IdentityDictionary(20);
		CmdPeriod.add(clearFunc);
	}

	*createNewBlockIfNeeded { | receiver, sender |
		//This happens when patching a simple number or array in to set a param
		if((receiver.isAlgaNode.not).or(sender.isAlgaNode.not), { ^nil });

		//Can't connect nodes from two different servers together
		if(receiver.server != sender.server, {
			("AlgaBlocksDict: Trying to create a block between two AlgaNodes on different servers").error;
			^receiver;
		});

		//Check if groups are instantiated, otherwise push action to scheduler
		if((receiver.group != nil).and(sender.group != nil), {
			this.createNewBlockIfNeeded_inner(receiver, sender)
		}, {
			receiver.scheduler.addAction(
				condition: { (receiver.group != nil).and(sender.group != nil) },
				func: {
					this.createNewBlockIfNeeded_inner(receiver, sender)
				}
			)
		});
	}

	*createNewBlockIfNeeded_inner { | receiver, sender |
		if(Alga.disableNodeOrdering.not, {
			var newBlockIndex;
			var newBlock;

			var receiverBlockIndex;
			var senderBlockIndex;
			var receiverBlock;
			var senderBlock;

			//Unpack things
			receiverBlockIndex = receiver.blockIndex;
			senderBlockIndex = sender.blockIndex;
			receiverBlock = blocksDict[receiverBlockIndex];
			senderBlock = blocksDict[senderBlockIndex];

			//Create new block if both connections didn't have any
			if((receiverBlockIndex == -1).and(senderBlockIndex == -1), {
				//"No block indices. Creating a new one".warn;

				newBlock = AlgaBlock(Alga.parGroup(receiver.server));
				if(newBlock == nil, { ^nil });

				newBlockIndex = newBlock.blockIndex;

				receiver.blockIndex = newBlockIndex;
				sender.blockIndex = newBlockIndex;

				//Add nodes to the block
				newBlock.addNode(receiver);
				newBlock.addNode(sender);

				//Add block to blocksDict
				blocksDict.put(newBlockIndex, newBlock);
			}, {
				//If they are not already in same block
				if(receiverBlockIndex != senderBlockIndex, {
					//Merge receiver with sender if receiver is not in a block yet
					if(receiverBlockIndex == -1, {
						//"No receiver block index. Set to sender's".warn;

						//Check block validity
						if(senderBlock == nil, {
							//("Invalid block with index " ++ senderBlockIndex).error;
							^nil;
						});

						//Add proxy to the block
						receiver.blockIndex = senderBlockIndex;
						senderBlock.addNode(receiver);

						//This is for the changed at the end of function...
						newBlockIndex = senderBlockIndex;
					}, {
						//Merge sender with receiver if sender is not in a block yet
						if(senderBlockIndex == -1, {

							//"No sender block index. Set to receiver".warn;

							if(receiverBlock == nil, {
								//("Invalid block with index " ++ receiverBlockIndex).error;
								^nil;
							});

							//Add proxy to the block
							sender.blockIndex = receiverBlockIndex;
							receiverBlock.addNode(sender);

							//This is for the changed at the end of function...
							newBlockIndex = receiverBlockIndex;
						}, {
							//Else, it means both nodes are already in blocks.
							//Create a new one and merge them into a new one (including relative ins/outs)

							//"Different block indices. Merge into a new one".warn;

							newBlock = AlgaBlock(Alga.parGroup(receiver.server));
							if(newBlock == nil, { ^nil });

							//Merge the old blocks into the new one
							newBlock.copyBlock(blocksDict[senderBlockIndex]);
							newBlock.copyBlock(blocksDict[receiverBlockIndex]);

							//Change index
							newBlockIndex = newBlock.blockIndex;

							//Remove previous blocks
							blocksDict.removeAt(receiverBlockIndex);
							blocksDict.removeAt(senderBlockIndex);

							//Add the two nodes to this new block
							receiver.blockIndex = newBlockIndex;
							sender.blockIndex = newBlockIndex;
							newBlock.addNode(receiver);
							newBlock.addNode(sender);

							//Finally, add the actual block to the dict
							blocksDict.put(newBlockIndex, newBlock);
						});
					});
				});
			});

			//If the function passes through (no actions taken), pass receiver's block instead
			if(newBlockIndex == nil, { newBlockIndex = receiver.blockIndex });

			//Actually reorder the block's nodes starting from the receiver
			this.rearrangeBlock(newBlockIndex, sender, receiver);
		});
	}

	*rearrangeBlock { | index, sender, receiver |
		var block = blocksDict[index];
		if(block != nil, {
			block.rearrangeBlock(sender, receiver);
		});
	}

	*rearrangeBlock_disconnect { | node |
		var index = node.blockIndex;
		var block = blocksDict[index];
		if(block != nil, {
			block.rearrangeBlock_disconnect(node);
		});
	}
}
