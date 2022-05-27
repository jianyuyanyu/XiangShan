/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package top

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import chisel3.stage.ChiselGeneratorAnnotation
import device.{AXI4RAMWrapper, SimJTAG}
import difftest._
import freechips.rocketchip.diplomacy.{DisableMonitors, LazyModule}
import freechips.rocketchip.util.ElaborationArtefacts
import top.TopMain.writeOutputFile
import utils.GTimer
import xiangshan.DebugOptionsKey

class SimTop(implicit p: Parameters) extends Module {
  val debugOpts = p(DebugOptionsKey)
  val useDRAMSim = debugOpts.UseDRAMSim

  val l_soc = LazyModule(new XSTop())
  val soc = Module(l_soc.module)

  val l_simMMIO = LazyModule(new SimMMIO(l_soc.misc.peripheralNode.in.head._2, l_soc.misc.l3FrontendAXI4Node.out.head._2))
  val simMMIO = Module(l_simMMIO.module)
  l_simMMIO.io_axi4 := DontCare
  l_simMMIO.io_axi4 <> soc.peripheral
  l_simMMIO.io_dma <> soc.dma

  if(!useDRAMSim){
    val l_simAXIMem = LazyModule(new AXI4RAMWrapper(
      l_soc.misc.memAXI4SlaveNode, 8L * 1024 * 1024 * 1024, useBlackBox = true
    ))
    val simAXIMem = Module(l_simAXIMem.module)
    l_simAXIMem.io_axi4 <> soc.memory
  }
  dontTouch(soc.io)

  soc.io.clock := clock
  soc.io.reset := reset.asAsyncReset
  soc.io.extIntrs := simMMIO.io.interrupt.intrVec
  soc.io.riscv_rst_vec.foreach(_ := 0x1ffff80000L.U)
  // soc.io.rtc_clock is a div100 of soc.io.clock
  val rtcClockDiv = 100
  val rtcTickCycle = rtcClockDiv / 2
  val rtcCounter = RegInit(0.U(log2Ceil(rtcTickCycle + 1).W))
  rtcCounter := Mux(rtcCounter === (rtcTickCycle - 1).U, 0.U, rtcCounter + 1.U)
  val rtcClock = RegInit(false.B)
  when (rtcCounter === 0.U) {
    rtcClock := ~rtcClock
  }
  soc.io.rtc_clock := rtcClock

  val success = Wire(Bool())
  val jtag = Module(new SimJTAG(tickDelay=3)(p)).connect(soc.io.systemjtag.jtag, clock, reset.asBool, ~reset.asBool, success)
  soc.io.systemjtag.reset := reset.asAsyncReset
  soc.io.systemjtag.mfr_id := 0.U(11.W)
  soc.io.systemjtag.part_number := 0.U(16.W)
  soc.io.systemjtag.version := 0.U(4.W)

  val io = IO(new Bundle(){
    val logCtrl = new LogCtrlIO
    val perfInfo = new PerfInfoIO
    val uart = new UARTIO
    val memAXI = if(useDRAMSim) soc.memory.cloneType else null
  })

  simMMIO.io.uart <> io.uart

  if(useDRAMSim){
    io.memAXI <> soc.memory
  }

  soc.xsx_ultiscan_ijtag := DontCare
  soc.xsl2_ultiscan_ijtag := DontCare
  soc.xsx_ultiscan_uscan := DontCare
  soc.xsl2_ultiscan_uscan := DontCare
  soc.hd2prf_in := DontCare
  soc.hsuspsr_in := DontCare
  soc.l1l2_mbist_jtag := DontCare
  if (soc.l3_mbist.ijtag.isDefined) {
    soc.l3_mbist.ijtag.get := DontCare
  }

  if (!debugOpts.FPGAPlatform && (debugOpts.EnableDebug || debugOpts.EnablePerfDebug)) {
    val timer = GTimer()
    val logEnable = (timer >= io.logCtrl.log_begin) && (timer < io.logCtrl.log_end)
    ExcitingUtils.addSource(logEnable, "DISPLAY_LOG_ENABLE")
    ExcitingUtils.addSource(timer, "logTimestamp")
  }

  if (!debugOpts.FPGAPlatform && debugOpts.EnablePerfDebug) {
    val clean = io.perfInfo.clean
    val dump = io.perfInfo.dump
    ExcitingUtils.addSource(clean, "XSPERF_CLEAN")
    ExcitingUtils.addSource(dump, "XSPERF_DUMP")
  }

  // Check and dispaly all source and sink connections
  ExcitingUtils.fixConnections()
  ExcitingUtils.checkAndDisplay()
}

object SimTop extends App {
  override def main(args: Array[String]): Unit = {
    // Keep this the same as TopMain except that SimTop is used here instead of XSTop
    val (config, firrtlOpts) = ArgParser.parse(args)
    XiangShanStage.execute(firrtlOpts, Seq(
      ChiselGeneratorAnnotation(() => {
        DisableMonitors(p => new SimTop()(p))(config)
      })
    ))
    ElaborationArtefacts.files.foreach{ case (extension, contents) =>
      writeOutputFile("./build", s"XSTop.${extension}", contents())
    }
  }
}
