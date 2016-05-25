package OpenSoC

import Chisel._
import scala.collection.mutable.HashMap

// abstract class ChannelQ(parms: Parameters) extends Module(parms) {
// 	val queueDepth = parms.get[Int]("queueDepth")
// 	val flitWidth : Int = io.in.flit.getWidth
	
// 	val io = new Bundle {
// 		val in = new Channel(parms)
// 		val out = new Channel(parms).flip()
// 	}
// }

class InjectionQState extends Chisel.Bundle {

    val idle            = UInt(0)
    val vcAllocGranted  = UInt(1)
    val xmit            = UInt(2)
    val hold            = UInt(3)
}

class InjectionQStateMgmt(parms: Parameters) extends Module(parms){

        val io = new Bundle {
            val inputBufferValid    = Bool(INPUT)
            val creditsAvailable    = Bool(INPUT)
            val inputIsTail         = Bool(INPUT)
            val vcAllocGranted      = Bool(INPUT)

            val currentState        = UInt().asOutput
        }

        val injQState   = new InjectionQState
        val curState    = Reg(init = injQState.idle)

        when(curState === injQState.idle){
            when(io.inputBufferValid && io.vcAllocGranted){
                curState := injQState.vcAllocGranted
            }.otherwise{
                curState := injQState.idle   
            } 
        }.elsewhen(curState === injQState.vcAllocGranted){
            when(io.creditsAvailable){
                curState := injQState.xmit
            }.otherwise{
                curState := injQState.vcAllocGranted
            }
        }.elsewhen(curState === injQState.xmit){
            when(!io.creditsAvailable){
                curState := injQState.hold
            }.otherwise{
            	when(io.inputIsTail) {
            		curState := injQState.idle
            	} .otherwise {
                	curState := injQState.xmit
                }
            }
        }.elsewhen(curState === injQState.hold){
            when(io.creditsAvailable){
                curState := injQState.xmit
            }.otherwise{
                curState := injQState.hold
            }
        }
    
        io.currentState := curState
}

class InjectionChannelQ(parms: Parameters) extends Module(parms) {

	val io = new Bundle {
		val in = new Channel(parms)
		val out = new ChannelVC(parms).flip()
	}

	val queueDepth          = parms.get[Int]("queueDepth")
	val numVCs              = parms.get[Int]("numVCs")
	val credThreshold		= parms.get[Int]("credThreshold")
	val vcArbCtor           = parms.get[Parameters=>Arbiter]("vcArbCtor")

	val flitWidth : Int     = io.in.flit.getWidth
	
	val creditGen           = Chisel.Module( new CreditGen( parms.child("MyGen")) ) 
	val creditCons          = (0 until numVCs).map( x =>
		                        Chisel.Module( new CreditCon( parms.child("MyCon", Map(
			                    ("numCreds"->Soft(queueDepth))))) 
                            ) )
	val vcArbiter           = Chisel.Module( vcArbCtor(parms.child("VCArbIQ", Map(
			                    ("numReqs"->Soft(numVCs))
		                        ))) 
                            )
    val injQStateMachine    = Chisel.Module(new InjectionQStateMgmt(parms))
    val InjectionQState     = new InjectionQState

	val queue               = Chisel.Module( new Chisel.Queue(new Flit(parms), queueDepth) )
	val chosen              = vcArbiter.io.chosen
	val regKeepRequesting   = (0 until numVCs).map( e =>
		                        Reg(Bool(false))
	                        )
	val regGrants           = (0 until numVCs).map( i =>
		                        Reg(Bool(false))
	                        )


    val releaseLockDelay    = Reg(Bool(false))
    releaseLockDelay        := queue.io.deq.bits.isTail() && queue.io.deq.valid 

	regGrants.zipWithIndex.foreach{case(e,i) => 
        e := vcArbiter.io.requests(i).grant
    }
	
    when ( queue.io.deq.bits.isHead() && queue.io.deq.valid){
	    regKeepRequesting.zipWithIndex.foreach{ case (e,i) =>
		    e := creditCons(i).io.outCredit
        }
    }

    val outCredits                          = Vec( creditCons.map(_.io.outCredit) )
    val almostOutCredits                    = Vec( creditCons.map(_.io.almostOut) )

    // --- State Machine Logic ---
    injQStateMachine.io.inputBufferValid    := queue.io.deq.valid
    injQStateMachine.io.vcAllocGranted      := vcArbiter.io.resource.valid
    injQStateMachine.io.creditsAvailable    := outCredits(chosen)  && ~almostOutCredits(chosen)
    injQStateMachine.io.inputIsTail         := queue.io.deq.bits.isTail() && queue.io.deq.valid
    // ------------------

    // ---- DEBUG ---  
    // assert(((queue.io.enq.ready && io.in.flitValid) || (~io.in.flitValid)),  "InjQ " + parms.path.head + ": queue overflow")
    //----------    

   
    //--- Input Logic ---
	queue.io.enq.bits       <> io.in.flit
	queue.io.enq.valid      := (UInt(queueDepth) - queue.io.count) > UInt(credThreshold) && io.in.flitValid
	creditGen.io.inGrant    := queue.io.deq.ready && queue.io.deq.valid
	//creditGen.io.outCredit  <> io.in.credit
	io.in.credit <> creditGen.io.outCredit
    //------------
	
    // -- Output Credit Logic
	creditCons.zipWithIndex.foreach{ case (e,i) => e.io.inCredit <> io.out.credit(i) }
	creditCons.zipWithIndex.foreach{ case (e,i) => e.io.inConsume := vcArbiter.io.requests(i).grant  && (injQStateMachine.io.currentState === InjectionQState.xmit) && outCredits(chosen) && queue.io.deq.valid} //( (vcArbiter.io.requests(i).grant && queue.io.deq.bits.isHead()) || (regGrants(i) && ~queue.io.deq.bits.isHead()) ) && e.io.outCredit && queue.io.deq.valid}
	io.out.flitValid := injQStateMachine.io.currentState === InjectionQState.xmit && queue.io.deq.valid 
	vcArbiter.io.requests.zipWithIndex.foreach{ case (e,i) => e.request := (creditCons(i).io.outCredit && queue.io.deq.bits.isHead() || (injQStateMachine.io.currentState >= InjectionQState.vcAllocGranted) ) } //regKeepRequesting(i) ) && queue.io.deq.valid }


	vcArbiter.io.requests.foreach{ case(e) => e.releaseLock := injQStateMachine.io.currentState === InjectionQState.idle } //releaseLockDelay}

	queue.io.deq.ready          := outCredits(chosen) && vcArbiter.io.resource.valid && (injQStateMachine.io.currentState === InjectionQState.xmit)
	vcArbiter.io.resource.ready := queue.io.deq.valid || (injQStateMachine.io.currentState >= InjectionQState.vcAllocGranted)

	val replaceVC = Chisel.Module( new ReplaceVCPort( parms ) )
	replaceVC.io.oldFlit <> queue.io.deq.bits
	replaceVC.io.newVCPort := chosen
    
	io.out.flit <> replaceVC.io.newFlit
}

class EjectionChannelQ(parms: Parameters) extends Module(parms) {
	val io = new Bundle {
		val in = new ChannelVC(parms)
		val out = new Channel(parms).flip()
	}
	val queueDepth = parms.get[Int]("queueDepth")
	val numVCs = parms.get[Int]("numVCs")
	val flitWidth : Int = io.in.flit.getWidth
	val creditGens = (0 until numVCs).map( x =>
		Chisel.Module( new CreditGen( parms.child("MyGen")) ) )
	val creditCon = Chisel.Module( new CreditCon( parms.child("MyCon", Map(
			("numCreds"->Soft(queueDepth))))) )

	val queue = Chisel.Module( new Chisel.Queue(new Flit(parms), queueDepth*numVCs) ) // Hack to account for VC credits
	
	creditGens.zipWithIndex.foreach{ case (e,i) => io.in.credit(i) <> e.io.outCredit}
	queue.io.enq.valid := io.in.flitValid//creditGens.map(_.io.outReady).reduceLeft( _ || _ )
	creditGens.map(_.io.inGrant := queue.io.deq.ready && queue.io.deq.valid)
	queue.io.enq.bits <> io.in.flit
	
	creditCon.io.inCredit <> io.out.credit
	creditCon.io.inConsume := queue.io.deq.valid && queue.io.deq.ready
	io.out.flitValid := queue.io.deq.valid
	queue.io.deq.ready := creditCon.io.outCredit
	io.out.flit <> queue.io.deq.bits

}

class VCIEChannelQ(parms: Parameters) extends Module(parms) {
	val io = new Bundle {
		val in = new Channel(parms)
		val out = new Channel(parms).flip()
	}
	val queueDepth = parms.get[Int]("queueDepth")
	val flitWidth : Int = io.in.flit.getWidth

	val iQueue = Chisel.Module ( new InjectionChannelQ( parms.child("iQ", Map(
		("queueDepth"->Soft(queueDepth))
	))) )
	val eQueue = Chisel.Module ( new EjectionChannelQ( parms.child("eQ", Map(
		("queueDepth"->Soft(queueDepth))
	))) )

	iQueue.io.in <> io.in
	eQueue.io.in <> iQueue.io.out
	io.out <> eQueue.io.out
}


class GenericChannelQ(parms: Parameters) extends Module(parms) {
	val io = new Bundle {
		val in = new Channel(parms)
		val out = new Channel(parms).flip()
	}
	val queueDepth = parms.get[Int]("queueDepth")
	val flitWidth : Int = io.in.flit.getWidth

	val creditGen = Chisel.Module ( new CreditGen( parms.child("MyGen")) )
	val creditCon = Chisel.Module ( new CreditCon( parms.child("MyCon", Map(
		("numCreds"->Soft(queueDepth))))) )

	val queue = Chisel.Module( new Chisel.Queue(new Flit(parms), queueDepth) )
	// val isTailQueue = Chisel.Module( new Chisel.Queue(new Bool(), queueDepth) )
	
	creditGen.io.outCredit <> io.in.credit
	queue.io.enq.valid := io.in.flitValid//creditGen.io.outReady
	creditGen.io.inGrant := queue.io.deq.ready && queue.io.deq.valid//queue.io.enq.ready 
	// isTailQueue.io.enq.valid := creditGen.io.inGrant && isTailQueue.io.enq.ready
	// isTailQueue.io.enq.bits <> creditGen.io.inIsTail
	queue.io.enq.bits <> io.in.flit
	
	creditCon.io.inCredit <> io.out.credit
	creditCon.io.inConsume := queue.io.deq.ready && queue.io.deq.valid
	io.out.flitValid := queue.io.deq.valid//queue.io.deq.valid //&& isTailQueue.io.deq.valid
	queue.io.deq.ready := creditCon.io.outCredit
	// isTailQueue.io.deq.ready := creditCon.io.outCredit && creditCon.io.inValid
	// creditCon.io.outIsTail := isTailQueue.io.deq.bits
	io.out.flit <> queue.io.deq.bits
	
	// queue.count
	// isTailQueue.count

	// val destCordWidth = parms.get[Int]("destCordWidth")
	// val destCordDim = parms.get[Int]("destCordDim")
	// val destination = Vec(destCordDim, UInt(width = destCordWidth))
}
