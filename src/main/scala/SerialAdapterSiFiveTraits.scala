package testchipip

import chisel3.experimental.IO
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci.ClockSinkDomain

trait CanHavePeripheryTLSerialControlOnly { this: BaseSubsystem =>
  private val portName = "serial-tl"
  val (desser, serial_tl) = p(SerialTLKey).map { params =>
    val client = this.subscribe(p(SerialTLAttachKey).masterWhere) // The bus for which this acts as a client
    val clientParams = TLMasterPortParameters.v1(
      clients = Seq(TLMasterParameters.v2(
        name     = "serial-tl",
        sourceId = IdRange(0, 1),
        emits    = TLMasterToSlaveTransferSizes.noAcquires, // TL2 allows only 1 transfer size, and coherent traffic is not expected here
      ))
    )

    // Assume we are in the same domain as our client-side binding.
    val domain = LazyModule(new ClockSinkDomain(name=Some(portName)))
    domain.clockNode := this.subsystemFixedClockNode

    val desser = domain { LazyModule(new TLDesser(
      w = params.width,
      params = Seq(clientParams)
    )) }

    client.coupleFrom(s"port_named_serial_tl_ctrl") {
      ( _
        := TLBuffer()
        := desser.node
      )
    }

    val inner_io = domain { InModuleBody {
      val inner_io = IO(new SerialIO(params.width)).suggestName("serial_tl")
      inner_io.out <> desser.module.io.ser.head.out
      desser.module.io.ser.head.in <> inner_io.in
      inner_io
    } }
    val outer_io = InModuleBody {
      val outer_io = IO(new ClockedIO(new SerialIO(params.width))).suggestName("serial_tl")
      val ser: SerialIO = if (params.asyncResetQueue) {
        SerialAdapter.asyncResetQueue(inner_io, domain.module.clock, domain.module.reset)
      } else {
        inner_io
      }
      outer_io.bits <> ser
      outer_io.clock := domain.module.clock
      outer_io
    }
    (Some(desser), Some(outer_io))
  }.getOrElse(None, None)
}
