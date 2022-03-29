# 1.2

## New features

- `AlgaPattern`: it is now possible to interpolate the `'dur'` parameter!

    ```SuperCollider
    (
    Alga.boot({
        //Pattern to modify
        a = AP((
            def: { SinOsc.ar * EnvPerc.ar(release: 0.1) * 0.5 },
            dur: 0.5
        )).play(chans:2);

        //Metronome
        b = AP((
            def: { EnvPerc.ar(release: SampleDur.ir); Impulse.ar(0) },
            dur: 1
        )).play(chans:2);
    })
    )

    //Using Env and resync
    a.interpDur(0.1, time: 5, shape: Env([0, 1, 0.5, 1], [2, 3, 4]))

    //No resync
    a.interpDur(0.5, time: 3, resync: false)

    //With resync + reset
    a.interpDur(Pseq([0.25, 0.25, 0.125], inf), time: 5, reset: true)

    //Other time params work too!
    a.interpStretch(0.5, time: 3, shape: Env([0, 1, 0.5, 1], [2, 3, 4]))
    ```

- `AlgaPattern` now supports scalar parameters. This is now the preferred way of interfacing with `Buffer` parameters. Also, scalar parameters can be used for optimization reasons for parameters that need to be set only once at the trigger of the `Synth` instance, without the overhead of the interpolator.

# 1.1.1

## Bug fixes

- `AlgaBlock`: check for new connections to trigger a re-order, and not just for `upperMostNodes`.

- `AlgaBlock`: fix `findAllUnusedFeedback` in order to be used on any new connection.

- `AlgaNode`: trigger `addActiveInOutNodes` on `replace` for old input connections.

- `AlgaPattern`: do not run `dur.next` twice on `replace`.

- `AlgaNode` and `AlgaPattern`: only the latest executed `replace` and `from` is executed on scheduled time. This allows the user to correct his/her/thier own mistakes while live coding with only having the latest iteration considered as valid code.

## New features

- Added the `AlgaQuant` class to schedule actions on specific bars:

    ```SuperCollider  
    (
    Alga.boot({
        a = AlgaPattern({ SinOsc.ar * EnvPerc.ar }).play(chans: 2)
    });
    )

    //Schedule at the next bar
    a.from(0.5, \dur, sched: AlgaQuant(1));

    //Schedule in two bars
    a.from(0.25, \dur, sched: AlgaQuant(2));

    //Schedule at the next bar + 1 beat
    a.from(0.5, \dur, sched: AlgaQuant(1, 1));
    ```
    
# 1.1.0

## Features

- Added the `interpShape` option. This allows to specify an interpolation shape in the form of an
  `Env`. All connection methods have also been updated to receive a `shape` argument to set the `Env`
  for the specific connection. Check the `Examples/AlgaNode/10_InterpShape.scd` below:

    ```SuperCollider
    (
    Alga.boot({
        a = AlgaNode(
            { SinOsc.ar(\freq.kr(440)) },
            interpTime: 3,
            interpShape: Env([0, 1, 0.5, 1], [1, 0.5, 1])
        ).play
    })
    )

    //The connection will use the Env declared in interpShape
    a <<.freq 220;

    //Temporary Env (standard ramp)
    a.from(880, \freq, shape: Env([0, 1]))

    //Using the original interpShape
    a <<.freq 440;
    ```

- Added multithreading support. Now, if booting `Alga` with `supernova`, it will automatically
  parallelize the arrangement of the nodes over the CPU cores. Check the
  `Examples/Extras/Multithreading.scd` below:

    ```SuperCollider
    //The code to test
    (
    c = {
        var reverb;

        //Definition of a bank of 50 sines
        AlgaSynthDef(\sines, {
            Mix.ar(Array.fill(50, { SinOsc.ar(Rand(200, 1000)) * 0.002 }))
        }).add;

        s.sync;

        //A reverb effect
        reverb = AlgaNode({ FreeVerb1.ar(\in.ar) }).play(chans: 2);

        //50 separated banks of 50 sine oscillators.
        //Their load will be spread across multiple CPU cores.
        50.do({
            var bank = AlgaNode(\sines);
            reverb <<+ bank;
        });

        //Print CPU usage
        fork {
            loop {
                ("CPU: " ++ s.avgCPU.asStringPrec(4) ++ " %").postln;
                1.wait;
            }
        }
    }
    )

    //Boot Alga with the supernova server
    //Alga will automatically spread the load across multiple CPU cores.
    Alga.boot(c, algaServerOptions: AlgaServerOptions(supernova: true, latency: 1));

    //Boot Alga with the standard scsynth server.
    //Note the higher CPU usage as the load is only on one CPU core.
    Alga.boot(c, algaServerOptions: AlgaServerOptions(latency: 1))
    ```

- Added support for using the same `AlgaSynthDefs` for both `AlgaNodes` and `AlgaPatterns`:

    ```SuperCollider
    //1
    (
    Alga.boot({
        AlgaSynthDef(\test, {
            SinOsc.ar(\freq.kr(440))
        }, sampleAccurate: true).add;

        s.sync;

        //a = AN(\test).play;

        b = AP((
            def: \test,
            amp: AlgaTemp({
                EnvPerc.ar(\atk.kr(0.01), \rel.kr(0.25), \curve.kr(-2), 0)
            }, sampleAccurate: true),
            dur: 0.5,
        )).play(chans:2)
    });
    )

    //2
    (
    Alga.boot({
        y = AP((
            def: { SinOsc.ar(\freq.kr(440)) * \amp.kr(1) },
            amp: AlgaTemp({
                EnvPerc.kr(\atk.kr(0.01), \rel.kr(0.25), \curve.kr(-2), 0)
            }),
            dur: 0.5,
        )).play(chans:2)
    })
    )

    //3
    (
    Alga.boot({
        y = AP((
            def: { SinOsc.ar(\freq.kr(440)) },
            amp: Pseq([
                AlgaTemp({
                    EnvPerc.ar(\atk.kr(0.01), \rel.kr(0.25), \curve.kr(-2), 0) * 0.25
                }, sampleAccurate: true),
                AlgaTemp({
                    EnvPerc.ar(\atk.kr(0.001), \rel.kr(0.1), \curve.kr(-2), 0) * 0.25
                }, sampleAccurate: true),
            ], inf),
            freq: Pwhite(440, 880),
            dur: Pwhite(0.01, 0.2),
        )).play(chans:2)
    })
    )
    ```

- Added support for `sustain / stretch / legato` to `AlgaPatterns`:

    ```SuperCollider
    //1
    (
    Alga.boot({
        a = AP((
            def: { SinOsc.ar * EnvGen.ar(Env.adsr, \gate.kr) }, //user can use \gate
            sustain: Pseq([1, 2], inf), //reserved keyword
            dur: 3
        )).play
    })
    )

    //2
    (
    Alga.boot({
        a = AP((
            def: { SinOsc.ar },
            amp: AlgaTemp({ EnvGen.ar(Env.adsr, \gate.kr) }, sampleAccurate: true),
            sustain: Pseq([1, 2], inf),
            dur: 3
        )).play
    })
    )

    //3
    (
    Alga.boot({
        a = AP((
            def: { SinOsc.ar(\freq.kr(440)) *
                EnvGen.kr(Env.adsr(\atk.kr(0.01), \del.kr(0.3), \sus.kr(0.5), \rel.kr(1.0)), \gate.kr, doneAction: 2) *
                \amp.kr(0.5)
            },
            dur: 4,
            freq: Pseed(1, Pexprand(100.0, 800.0).round(27.3)),
            amp: Pseq([0.3, 0.2], inf),
            //sustain: 4,
            legato: Pseq([1, 0.5], inf),
            rel: 0.1,
            callback: { |ev| ev.postln },
        )).sustainToDur_(true).play
    })
    )

    //Same as:
    (
    Alga.boot({
        a = AP((
            def: { SinOsc.ar(\freq.kr(440)) *
                EnvGen.kr(Env.adsr(\atk.kr(0.01), \del.kr(0.3), \sus.kr(0.5), \rel.kr(1.0)), \gate.kr, doneAction: 2) *
                \amp.kr(0.5)
            },
            dur: 4,
            freq: Pseed(1, Pexprand(100.0, 800.0).round(27.3)),
            amp: Pseq([0.3, 0.2], inf),
            sustain: 4,
            legato: Pseq([1, 0.5], inf),
            rel: 0.1,
            callback: { |ev| ev.postln },
        )).play
    })
    )
    ```

- Added the `AlgaStep` class. This class allows to schedule actions not on a specific beat,
  but at a specific "pattern trigger" that will happen in the future:

    ```SuperCollider
    (
    Alga.boot({
        a = AlgaPattern((
            def: { SinOsc.ar(\freq.ar(440)) * EnvPerc.ar * 0.5 },
            dur: 1
        )).play
    })
    )

    //Schedule 2 triggers from now
    a.from(Pseq([220, 880], inf), \freq, time: 1, sched: AlgaStep(2))

    //Schedule at the next trigger
    a.from(Pseq([0.5, 0.25], inf), \dur, sched: AlgaStep(0))
    ```

- Added the `AlgaProxySpace` class. This allows to quickly define `AlgaNodes` and `AlgaPatterns` in a fashion that
  is similar to SC's `ProxySpace`. Check the help files and the Examples folder for a deeper look at all of its features.

  ```SuperCollider
  p = AlgaProxySpace.boot;

  //A simple node
  ~a = { SinOsc.ar(100) };

  //Use it as FM input for another node
  ~b.play(chans:2);
  ~b.interpTime = 2;
  ~b.playTime = 0.5;
  ~b = { SinOsc.ar(\freq.ar(~a).range(200, 400)) };

  //Replace
  ~a = { SinOsc.ar(440) };

  //New connection as usual
  ~b.from({ LFNoise1.ar(100) }, \freq, time:3)
  ```

- Introducing `AlgaPatternPlayer`, a new way to trigger and dispatch `AlgaPatterns`:

    ```SuperCollider
    (
    Alga.boot({
        //Define and start an AlgaPatternPlayer
        ~player = AlgaPatternPlayer((
            dur: Pwhite(0.2, 0.7),
            freq: Pseq([440, 880], inf)
        )).play;

        //Use ~player for both indexing values and triggering the pattern
        ~pattern = AP((
            def: { SinOsc.ar(\freq.kr + \freq2.kr) * EnvPerc.ar },
            freq: ~player[\freq],
            freq2: ~player.read({ | freq |
                if(freq == 440, { freq * 2 }, { 0 })
            }),
        ), player: ~player).play;
    })
    )

    //Interpolation still works
    ~pattern.from(~player.({ | freq | freq * 0.5 }), \freq, time: 5) //.value == .read
    ~pattern.from(~player.({ | freq | freq * 2 }), \freq2, time: 5)

    //Modify dur
    ~player.from(0.5, \dur, sched: AlgaStep(3))

    //If modifying player, the interpolation is still triggered on the children
    ~player.from(Pseq([330, 660], inf), \freq, time: 5)

    //Removing a player stops the pattern triggering
    ~pattern.removePlayer;
    ```

