package me.cortex.voxy.common.util.cpu;

import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinNT.PROCESSOR_RELATIONSHIP;
import com.sun.jna.platform.win32.WinNT.SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Arrays;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;
import org.lwjgl.system.Platform;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.CentralProcessor.LogicalProcessor;
import oshi.hardware.CentralProcessor.PhysicalProcessor;

public class CpuLayout {
   public static final CpuLayout.Core[] CORES;

   private CpuLayout() {
   }

   public static void setThreadAffinity(CpuLayout.Core... cores) {
      CpuLayout.Affinity[] affinity = new CpuLayout.Affinity[cores.length];

      for (int i = 0; i < cores.length; i++) {
         affinity[i] = cores[i].affinity;
      }

      setThreadAffinity(affinity);
   }

   public static void setThreadAffinity(CpuLayout.Affinity... affinities) {
      Platform platform = Platform.get();
      if (platform == Platform.WINDOWS) {
         long[] msks = new long[affinities.length];
         short[] groups = new short[affinities.length];
         Arrays.fill(groups, (short)-1);
         int i = 0;

         for (CpuLayout.Affinity a : affinities) {
            int idx = 0;

            while (idx < i && groups[idx] != a.group) {
               idx++;
            }

            if (idx == i) {
               groups[idx] = a.group;
               i++;
            }

            msks[idx] |= a.msk;
         }

         ThreadUtils.SetThreadSelectedCpuSetMasksWin32(Arrays.copyOf(msks, i), Arrays.copyOf(groups, i));
      } else if (platform == Platform.LINUX) {
         Arrays.sort(affinities, (ax, b) -> ax.group - b.group);
         long[] msks = new long[affinities.length];

         for (int i = 0; i < affinities.length; i++) {
            msks[i] = affinities[i].msk;
         }

         ThreadUtils.schedSetaffinityLinux(msks);
      } else {
         Logger.error("Don't know how to set thread affinity on this platform.");
      }
   }

   private static CpuLayout.Core[] generateCoreLayoutWindows() {
      SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX[] cores = Kernel32Util.getLogicalProcessorInformationEx(0);
      boolean allSameClass = true;

      for (SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX coreO : cores) {
         PROCESSOR_RELATIONSHIP core = (PROCESSOR_RELATIONSHIP)coreO;
         allSameClass &= core.efficiencyClass == 0;
      }

      int i = 0;
      CpuLayout.Core[] res = new CpuLayout.Core[cores.length];

      for (SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX coreO : cores) {
         PROCESSOR_RELATIONSHIP core = (PROCESSOR_RELATIONSHIP)coreO;
         boolean smt = (core.flags & 1) == 1;
         byte eclz = core.efficiencyClass;
         if (core.groupMask.length != 1) {
            throw new IllegalStateException("Unsupported architecture");
         }

         long msk = core.groupMask[0].mask.longValue();
         if (Long.bitCount(msk) > 1 != smt) {
            throw new IllegalStateException("Logic issue");
         }

         res[i++] = new CpuLayout.Core(!allSameClass && eclz == 0, new CpuLayout.Affinity(msk, core.groupMask[0].group));
      }

      sort(res);
      return res;
   }

   private static CpuLayout.Core[] generateCoreLayoutLinux() {
      CentralProcessor processor = new SystemInfo().getHardware().getProcessor();
      Int2ObjectOpenHashMap<CpuLayout.Affinity> affinityMsk = new Int2ObjectOpenHashMap();

      for (LogicalProcessor thread : processor.getLogicalProcessors()) {
         CpuLayout.Affinity aff = (CpuLayout.Affinity)affinityMsk.getOrDefault(
            thread.getPhysicalProcessorNumber(), new CpuLayout.Affinity(0L, (short)thread.getProcessorGroup())
         );
         if (thread.getProcessorGroup() != aff.group) {
            throw new IllegalStateException();
         }

         affinityMsk.put(
            thread.getPhysicalProcessorNumber(), new CpuLayout.Affinity(aff.msk | 1L << thread.getProcessorNumber(), (short)thread.getProcessorGroup())
         );
      }

      CpuLayout.Core[] cores = new CpuLayout.Core[processor.getPhysicalProcessors().size()];
      int i = 0;
      boolean allSameEfficiency = true;

      for (PhysicalProcessor core : processor.getPhysicalProcessors()) {
         if (core.getEfficiency() != 0) {
            allSameEfficiency = false;
            break;
         }
      }

      for (PhysicalProcessor corex : processor.getPhysicalProcessors()) {
         CpuLayout.Affinity aff = (CpuLayout.Affinity)affinityMsk.remove(corex.getPhysicalProcessorNumber());
         if (aff == null) {
            throw new IllegalStateException();
         }

         cores[i++] = new CpuLayout.Core(corex.getEfficiency() == 0 && !allSameEfficiency, aff);
      }

      sort(cores);
      return cores;
   }

   private static void sort(CpuLayout.Core[] cores) {
      Arrays.sort(cores, (a, b) -> {
         if (a.isEfficiency == b.isEfficiency) {
            int c = Short.compareUnsigned(a.affinity.group, b.affinity.group);
            return c == 0 ? Long.compareUnsigned(a.affinity.msk, b.affinity.msk) : c;
         } else {
            return a.isEfficiency ? 1 : -1;
         }
      });
   }


   public static int getCoreCount() {
      return CORES == null ? Runtime.getRuntime().availableProcessors() : CORES.length;
   }

   static {
      CpuLayout.Core[] cores = null;

      try {
         if (Platform.get() == Platform.WINDOWS) {
            cores = generateCoreLayoutWindows();
         } else if (Platform.get() == Platform.LINUX) {
            cores = generateCoreLayoutLinux();
         }
      } catch (Exception var2) {
         Logger.error("Failed to generate cpu core layout, falling back to null: ", var2);
      }

      CORES = cores;
   }

   public record Affinity(long msk, short group) {
   }

   public record Core(boolean isEfficiency, CpuLayout.Affinity affinity) {
   }
}
