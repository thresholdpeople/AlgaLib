AlgaNode {
	var <server;

	//This is the time when making a new connection to this proxy
	var <fadeTime = 0;

	//This is the longestFadeTime between all the outConnections.
	//it's used when .replacing a node connected to something, in order for it to be kept alive
	//for all the connected nodes to run their interpolator on it
	var <fadeTimeConnections, <longestFadeTime = 0;

	var <objClass;
	var <synthDef;

	var <controlNames;

	var <numChannels, <rate;

	var <group, <synthGroup, <normGroup, <interpGroup;
	var <synth, <normSynths, <interpSynths;
	var <synthBus, <normBusses, <interpBusses;

	var <inConnections, <outConnections;

	var <isPlaying = false;
	var <toBeCleared = false;

	*new { | obj, server, fadeTime = 0 |
		^super.new.init(obj, server, fadeTime)
	}

	init { | obj, argServer, argFadeTime = 0 |
		//starting fadeTime (using the setter so it also sets longestFadeTime)
		this.fadeTime_(argFadeTime);

		//Default server if not specified otherwise
		if(argServer == nil, { server = Server.default }, { server = argServer });

		//param -> ControlName
		controlNames = Dictionary(10);

		//Per-argument dictionaries of interp/norm Busses and Synths belonging to this AlgaNode
		normBusses   = Dictionary(10);
		interpBusses = Dictionary(10);
		normSynths   = Dictionary(10);
		interpSynths = Dictionary(10);

		//Per-argument connections to this AlgaNode
		inConnections = Dictionary.new(10);

		//outConnections are not indexed by param name, as they could be attached to multiple nodes with same param name.
		//they are indexed by identity of the connected node, and then it contains a Set of all parameters
		//that it controls in that node (AlgaNode -> Set[\freq, \amp ...])
		outConnections = Dictionary.new(10);

		//Keeps all the fadeTimes of the connected nodes
		fadeTimeConnections = Dictionary.new(10);

		//Dispatch node creation
		this.dispatchNode(obj, true);
	}

	fadeTime_ { | val |
		fadeTime = val;
		this.calculateLongestFadeTime;
	}

	ft {
		^fadeTime;
	}

	ft_ { | val |
		this.fadeTime_(val);
	}

	calculateLongestFadeTime {
		longestFadeTime = fadeTime;
		fadeTimeConnections.do({ | val |
			if(val > longestFadeTime, { longestFadeTime = val });
		});
	}

	createAllGroups {
		if(group == nil, {
			group = Group(this.server);
			synthGroup = Group(group); //could be ParGroup here for supernova + patterns...
			normGroup = Group(group);
			interpGroup = Group(group);
		});
	}

	resetGroups {
		//Reset values
		group = nil;
		synthGroup = nil;
		normGroup = nil;
		interpGroup = nil;
	}

	//Groups (and state) will be reset only if they are nil AND they are set to be freed.
	//the toBeCleared variable can be changed in real time, if AlgaNode.replace is called while
	//clearing is happening!
	freeAllGroups { | now = false |
		if((group != nil).and(toBeCleared), {
			if(now, {
				//Free now
				group.free;

				//this.resetGroups;
			}, {
				//Wait fadeTime, then free
				fork {
					longestFadeTime.wait;

					group.free;

					//this.resetGroups;
				};
			});
		});
	}

	createSynthBus {
		synthBus = AlgaBus(server, numChannels, rate);
		if(isPlaying, { synthBus.play });
	}

	createInterpNormBusses {
		controlNames.do({ | controlName |
			var paramName = controlName.name;

			var argDefaultVal = controlName.defaultValue;
			var paramRate = controlName.rate;
			var paramNumChannels = controlName.numChannels;

			//interpBusses have 1 more channel for the envelope shape
			interpBusses[paramName] = AlgaBus(server, paramNumChannels + 1, paramRate);
			normBusses[paramName] = AlgaBus(server, paramNumChannels, paramRate);
		});
	}

	createAllBusses {
		this.createInterpNormBusses;
		this.createSynthBus;
	}

	freeSynthBus { | now = false |
		if(now, {
			if(synthBus != nil, { synthBus.free });
		}, {
			//if forking, this.synthBus could have changed, that's why this is needed
			var prevSynthBus = synthBus.copy;
			fork {
				longestFadeTime.wait;

				if(prevSynthBus != nil, { prevSynthBus.free });
			}
		});
	}

	freeInterpNormBusses { | now = false |

		if(now, {
			//Free busses now
			if(normBusses != nil, {
				normBusses.do({ | normBus |
					if(normBus != nil, { normBus.free });
				});
			});

			if(normBusses != nil, {
				normBusses.do({ | interpBus |
					if(interpBus != nil, { interpBus.free });
				});
			});
		}, {
			//Dictionary need to be deepcopied
			var prevNormBusses = normBusses.copy;
			var prevInterpBusses = interpBusses.copy;

			//Free prev busses after fadeTime
			fork {
				longestFadeTime.wait;

				if(prevNormBusses != nil, {
					prevNormBusses.do({ | normBus |
						if(normBus != nil, { normBus.free });
					});
				});

				if(prevInterpBusses != nil, {
					prevInterpBusses.do({ | interpBus |
						if(interpBus != nil, { interpBus.free });
					});
				});
			}
		});
	}

	freeAllBusses { | now = false |
		this.freeSynthBus(now);
		this.freeInterpNormBusses(now);
	}

	//dispatches controlnames / numChannels / rate according to obj class
	dispatchNode { | obj, initGroups = false |
		objClass = obj.class;

		//If there is a synth playing, set its instantiated status to false:
		//this is mostly needed for .replace to work properly and wait for the new synth
		//to be instantiated!
		if(synth != nil, { synth.instantiated = false });

		//Symbol
		if(objClass == Symbol, {
			this.dispatchSynthDef(obj, initGroups);
		}, {
			//Function
			if(objClass == Function, {
				this.dispatchFunction(obj, initGroups);
			}, {
				("AlgaNode: class '" ++ objClass ++ "' is invalid").error;
				this.clear;
			});
		});
	}

	//Dispatch a SynthDef
	dispatchSynthDef { | obj, initGroups = false |
		var synthDescControlNames;
		var synthDesc = SynthDescLib.global.at(obj);

		if(synthDesc == nil, {
			("Invalid AlgaSynthDef: '" ++ obj.asString ++"'").error;
			this.clear;
			^nil;
		});

		synthDef = synthDesc.def;

		if(synthDef.class != AlgaSynthDef, {
			("Invalid AlgaSynthDef: '" ++ obj.asString ++"'").error;
			this.clear;
			^nil;
		});

		synthDescControlNames = synthDesc.controls;
		this.createControlNames(synthDescControlNames);

		numChannels = synthDef.numChannels;
		rate = synthDef.rate;

		//Create all utilities
		if(initGroups, { this.createAllGroups });
		this.createAllBusses;

		//Create actual synths
		this.createAllSynths(synthDef.name);
	}

	//Dispatch a Function
	dispatchFunction { | obj, initGroups = false |
		//Need to wait for server's receiving the sdef
		fork {
			var synthDescControlNames;

			synthDef = AlgaSynthDef(("alga_" ++ UniqueID.next).asSymbol, obj).send(server);
			server.sync;

			synthDescControlNames = synthDef.asSynthDesc.controls;
			this.createControlNames(synthDescControlNames);

			numChannels = synthDef.numChannels;
			rate = synthDef.rate;

			//Create all utilities
			if(initGroups, { this.createAllGroups });
			this.createAllBusses;

			//Create actual synths
			this.createAllSynths(synthDef.name);
		};
	}

	//Remove \fadeTime \out and \gate and generate controlNames dict entries
	createControlNames { | synthDescControlNames |
		synthDescControlNames.do({ | controlName |
			var paramName = controlName.name;
			if((controlName.name != \fadeTime).and(
				controlName.name != \out).and(
				controlName.name != \gate), {
				controlNames[controlName.name] = controlName;
			});
		});
	}

	resetSynth {
		//Set to nil (should it fork?)
		synth = nil;
		synthDef = nil;
		controlNames.clear;
		numChannels = 0;
		rate = nil;
	}

	resetInterpNormSynths {
		//Just reset the Dictionaries entries
		interpSynths.clear;
		normSynths.clear;
	}

	//Synth writes to the synthBus
	//Synth always uses longestFadeTime, in order to make sure that everything connected to it
	//will have time to run fade ins and outs
	createSynth { | defName |
		//synth's fadeTime is longestFadeTime!
		synth = AlgaSynth.new(
			defName,
			[\out, synthBus.index, \fadeTime, longestFadeTime],
			synthGroup
		);
	}

	//This should take in account the nextNode's numChannels when making connections
	createInterpNormSynths {
		controlNames.do({ | controlName |
			var interpSymbol, normSymbol;
			var interpBus, normBus, interpSynth, normSynth;

			var paramName = controlName.name;
			var paramNumChannels = controlName.numChannels.asString;
			var paramRate = controlName.rate.asString;
			var argDefault = controlName.defaultValue;

			//e.g. \algaInterp_audio1_control1
			interpSymbol = (
				"algaInterp_" ++
				paramRate.asString ++
				paramNumChannels.asString ++
				"_" ++
				paramRate ++
				paramNumChannels
			).asSymbol;

			//e.g. \algaNorm_audio1
			normSymbol = (
				"algaNorm_" ++
				paramRate ++
				paramNumChannels
			).asSymbol;

			interpBus = interpBusses[paramName];
			normBus = normBusses[paramName];

			//USES fadeTime!!
			interpSynth = AlgaSynth.new(
				interpSymbol,
				[\in, argDefault, \out, interpBus.index, \fadeTime, fadeTime],
				interpGroup
			);

			//USES fadeTime!!
			normSynth = AlgaSynth.new(
				normSymbol,
				[\args, interpBus.busArg, \out, normBus.index, \fadeTime, fadeTime],
				normGroup
			);

			interpSynths[paramName] = interpSynth;
			normSynths[paramName] = normSynth;

			//Connect right away, as th normSynth will normalize the fading in value of
			//the interpSynth set to default, so no need to wait for fadeTime to make connection
			synth.set(paramName, normBus.busArg);
		});
	}

	createAllSynths { | defName |
		this.createSynth(defName);
		this.createInterpNormSynths;
	}

	createInterpSynthAtParam { | sender, param = \in |
		var controlName;
		var paramNumChannels, paramRate;
		var senderNumChannels, senderRate;
		var interpSymbol;

		var interpBus, interpSynth;

		controlName = controlNames[param];

		paramNumChannels = controlName.numChannels;
		paramRate = controlName.rate;
		senderNumChannels = sender.numChannels;
		senderRate = sender.rate;

		interpSymbol = (
			"algaInterp_" ++
			senderRate ++
			senderNumChannels ++
			"_" ++
			paramRate ++
			paramNumChannels
		).asSymbol;

		interpBus = interpBusses[param];

		//new interp synth, with input connected to sender and output to the interpBus
		//USES fadeTime!!
		interpSynth = AlgaSynth.new(
			interpSymbol,
			[\in, sender.synthBus.busArg, \out, interpBus.index, \fadeTime, fadeTime],
			interpGroup
		);

		//Add synth to interpSynths
		interpSynths[param] = interpSynth;
	}

	//Default now and fadetime to true for synths.
	//Synth always uses longestFadeTime, in order to make sure that everything connected to it
	//will have time to run fade ins and outs
	freeSynth { | useFadeTime = true, now = true |
		if(now, {
			if(synth != nil, {
				//synth's fadeTime is longestFadeTime!
				synth.set(\gate, 0, \fadeTime,  if(useFadeTime, { longestFadeTime }, {0}));

				//this.resetSynth;
			});
		}, {
			fork {
				//longestFadeTime?
				longestFadeTime.wait;

				if(synth != nil, {
					synth.set(\gate, 0, \fadeTime,  0);

					//this.resetSynth;
				});
			}
		});
	}

	//Default now and fadetime to true for synths
	freeInterpNormSynths { | useFadeTime = true, now = true |

		if(now, {
			//Free synths now
			interpSynths.do({ | interpSynth |
				interpSynth.set(\gate, 0, \fadeTime, if(useFadeTime, { longestFadeTime }, {0}));
			});

			normSynths.do({ | normSynth |
				normSynth.set(\gate, 0, \fadeTime, if(useFadeTime, { longestFadeTime }, {0}));
			});

			//this.resetInterpNormSynths;

		}, {
			//Dictionaries need to be deep copied
			var prevInterpSynths = interpSynths.copy;
			var prevNormSynths = normSynths.copy;

			fork {
				//Wait, then free synths
				longestFadeTime.wait;

				prevInterpSynths.do({ | interpSynth |
					interpSynth.set(\gate, 0, \fadeTime, 0);
				});

				prevNormSynths.do({ | normSynth |
					normSynth.set(\gate, 0, \fadeTime, 0);
				});

				//this.resetInterpNormSynths;
			}
		});
	}

	freeAllSynths { | useFadeTime = true, now = true |
		this.freeSynth(useFadeTime, now);
		this.freeInterpNormSynths(useFadeTime, now);
	}

	//This is only used in connection situations
	freeInterpSynthAtParam { | param = \in |
		var interpSynthAtParam = interpSynths[param];
		if(interpSynthAtParam == nil, { ("Invalid param for interp synth to free: " ++ param).error; ^this });
		interpSynthAtParam.set(\gate, 0, \fadeTime, fadeTime);
	}

	resetConnections {
		if(toBeCleared, {
			inConnections.clear;
			outConnections.clear;
		});
	}

	//param -> AlgaNode
	addInConnection { | sender, param = \in |
		inConnections[param] = sender;
	}

	//AlgaNode -> Set[params]
	addOutConnection { | receiver, param = \in |
		if(outConnections[receiver] == nil, {
			outConnections[receiver] = Set[param];
		}, {
			outConnections[receiver].add(param);
		});
	}

	//add entries to the inConnections / outConnections of the two AlgaNodes
	addConnections { | sender, param = \in |
		//This will replace the entries on new connection
		this.addInConnection(sender, param);
		sender.addOutConnection(this, param);

		//Add to fadeTimeConnections and recalculate longestFadeTime
		sender.fadeTimeConnections[this] = this.fadeTime;
		sender.calculateLongestFadeTime;
	}

	newInterpConnectionAtParam { | sender, param = \in |
		var controlName = controlNames[param];
		if(controlName == nil, { ("Invalid param to create a new interp synth for: " ++ param).error; ^this;});

		//Free prev interp synth (fades out)
		this.freeInterpSynthAtParam(param);

		//Spawn new interp synth (fades in)
		this.createInterpSynthAtParam(sender, param);

		//Add proper inConnections / outConnections
		this.addConnections(sender, param);
	}

	//implements receiver <<.param sender
	makeConnection { | sender, param = \in |
		//Connect interpSynth to the sender's synthBus
		AlgaSpinRoutine.waitFor( { (this.instantiated).and(sender.instantiated) }, {
			this.newInterpConnectionAtParam(sender, param);
		});
	}

	//arg is the sender
	<< { | sender, param = \in |
		if(sender.class == AlgaNode, {
			this.makeConnection(sender, param);
		}, {
			("Trying to make a connection with an invalid AlgaNode: " ++ sender).error;
		});
	}

	//arg is the receiver
	>> { | receiver, param = \in |
		receiver.makeConnection(this, param);
	}

	//resets to the default value in controlNames
	<| { | param = \in |

	}

	//All synths must be instantiated (including interpolators and normalizers)
	instantiated {
		if(synth == nil, { ^false });

		interpSynths.do({ | interpSynth |
			if(interpSynth.instantiated == false, { ^false });
		});

		normSynths.do({ | normSynth |
			if(normSynth.instantiated == false, { ^false });
		});

		//Lastly, the actual synth
		^synth.instantiated;
	}

	//Remake both inConnections and outConnections
	replaceConnections {
		//inConnections
		inConnections.keysValuesDo({ | param, sender |
			this.makeConnection(sender, param);
		});

		//outConnections
		outConnections.keysValuesDo({ | receiver, paramSet |
			paramSet.do({ | param |
				receiver.makeConnection(this, param);
			});
		});
	}

	replace { | obj |
		//re-init groups if clear was used
		var initGroups = if(group == nil, { true }, { false });

		//In case it has been set to true when clearing, then replacing before clear ends!
		toBeCleared = false;

		//Free previous ones
		this.freeAllSynths;

		//Should perhaps check for new numChannels / rate, instead of just deleting it all
		this.freeAllBusses;

		//New one
		this.dispatchNode(obj, initGroups);

		//Re-enstablish connections that were already in place
		this.replaceConnections;
	}

	clear {
		fork {
			this.freeSynth;

			toBeCleared = true;

			//Wait time before clearing groups and busses
			longestFadeTime.wait;
			this.freeInterpNormSynths(false, true);
			this.freeAllGroups(true);
			this.freeAllBusses(true);

			//Reset connection dicts
			this.resetConnections;
		}
	}

	play {
		isPlaying = true;
		synthBus.play;
	}
}