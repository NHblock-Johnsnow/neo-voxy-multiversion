package me.cortex.voxy.client.core.util;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

public class RingTracker {
   private final Long2ByteOpenHashMap operations = new Long2ByteOpenHashMap(8192);
   private final int[] boundDist;
   private final int radius;
   private int centerX;
   private int centerZ;

   public RingTracker(int radius, int centerX, int centerZ, boolean fill) {
      this(null, radius, centerX, centerZ, fill);
   }

   public RingTracker(RingTracker stealFrom, int radius, int centerX, int centerZ, boolean fill) {
      this.centerX = centerX;
      this.centerZ = centerZ;
      this.radius = radius;
      this.boundDist = this.generateBoundingHalfCircleDistance(radius);
      if (stealFrom != null) {
         this.operations.putAll(stealFrom.operations);
         stealFrom.operations.clear();
      }

      if (fill) {
         this.fillRing(true);
      }
   }

   private static long pack(int x, int z) {
      return Integer.toUnsignedLong(x) | Integer.toUnsignedLong(z) << 32;
   }

   private void fillRing(boolean load) {
      for (int i = 0; i <= this.radius * 2; i++) {
         int x = this.centerX + i - this.radius;
         int d = this.boundDist[i];

         for (int z = this.centerZ - d; z <= this.centerZ + d; z++) {
            int res = this.operations.addTo(pack(x, z), (byte)(load ? 1 : -1));
            if (load && 0 < res || !load && res < 0) {
               throw new IllegalStateException();
            }
         }
      }
   }

   public void unload() {
      this.fillRing(false);
   }

   public void moveCenter(int x, int z) {
      if (this.radius + 1 >= Math.abs(x - this.centerX) && this.radius + 1 >= Math.abs(z - this.centerZ)) {
         if (x != this.centerX) {
            this.moveX(x - this.centerX);
         }

         if (z != this.centerZ) {
            this.moveZ(z - this.centerZ);
         }
      } else {
         this.fillRing(false);
         this.centerX = x;
         this.centerZ = z;
         this.fillRing(true);
      }
   }

   private void moveZ(int delta) {
      if (delta != 0) {
         if (delta != -1 && delta != 1) {
            int sDelta = Integer.signum(delta);

            for (int i = 0; i <= this.radius * 2; i++) {
               int x = this.centerX + i - this.radius;
               int d = this.boundDist[i] * sDelta;
               int pz = this.centerZ + d;

               for (int z = pz + (sDelta < 0 ? delta : 1); z <= pz + (sDelta < 0 ? -1 : delta); z++) {
                  if (0 < this.operations.addTo(pack(x, z), (byte)1)) {
                     throw new IllegalStateException();
                  }
               }

               int nz = this.centerZ - d;

               for (int zx = nz + (sDelta < 0 ? delta + 1 : 0); zx < nz + (sDelta < 0 ? 1 : delta); zx++) {
                  if (this.operations.addTo(pack(x, zx), (byte)-1) < 0) {
                     throw new IllegalStateException();
                  }
               }
            }

            this.centerZ += delta;
         } else {
            for (int i = 0; i <= this.radius * 2; i++) {
               int x = this.centerX + i - this.radius;
               int d = this.boundDist[i] * delta;
               int pz = this.centerZ + d + delta;
               int nz = this.centerZ - d;
               if (0 < this.operations.addTo(pack(x, pz), (byte)1)) {
                  throw new IllegalStateException("x: " + x + ", z: " + pz + " state: " + this.operations.get(pack(x, pz)));
               }

               if (this.operations.addTo(pack(x, nz), (byte)-1) < 0) {
                  throw new IllegalStateException("x: " + x + ", z: " + nz + " state: " + this.operations.get(pack(x, nz)));
               }
            }

            this.centerZ += delta;
         }
      }
   }

   private void moveX(int delta) {
      if (delta != 0) {
         if (delta != -1 && delta != 1) {
            int sDelta = Integer.signum(delta);

            for (int i = 0; i <= this.radius * 2; i++) {
               int z = this.centerZ + i - this.radius;
               int d = this.boundDist[i] * sDelta;
               int px = this.centerX + d;

               for (int x = px + (sDelta < 0 ? delta : 1); x <= px + (sDelta < 0 ? -1 : delta); x++) {
                  if (0 < this.operations.addTo(pack(x, z), (byte)1)) {
                     throw new IllegalStateException();
                  }
               }

               int nx = this.centerX - d;

               for (int xx = nx + (sDelta < 0 ? delta + 1 : 0); xx < nx + (sDelta < 0 ? 1 : delta); xx++) {
                  if (this.operations.addTo(pack(xx, z), (byte)-1) < 0) {
                     throw new IllegalStateException();
                  }
               }
            }

            this.centerX += delta;
         } else {
            for (int i = 0; i <= this.radius * 2; i++) {
               int z = this.centerZ + i - this.radius;
               int d = this.boundDist[i] * delta;
               int px = this.centerX + d + delta;
               int nx = this.centerX - d;
               if (0 < this.operations.addTo(pack(px, z), (byte)1)) {
                  throw new IllegalStateException();
               }

               if (this.operations.addTo(pack(nx, z), (byte)-1) < 0) {
                  throw new IllegalStateException();
               }
            }

            this.centerX += delta;
         }
      }
   }

   public int process(int N, RingTracker.IUpdateConsumer onAdd, RingTracker.IUpdateConsumer onRemove) {
      if (this.operations.isEmpty()) {
         return 0;
      } else {
         ObjectIterator<Entry> iter = this.operations.long2ByteEntrySet().fastIterator();
         int i = 0;

         while (iter.hasNext() && N-- != 0) {
            Entry entry = (Entry)iter.next();
            if (entry.getByteValue() == 0) {
               iter.remove();
               N++;
            } else {
               i++;
               byte op = entry.getByteValue();
               if (op != 1 && op != -1) {
                  throw new IllegalStateException();
               }

               boolean isAdd = op == 1;
               long pos = entry.getLongKey();
               int x = (int)(pos & 4294967295L);
               int z = (int)(pos >>> 32 & 4294967295L);
               if (isAdd) {
                  onAdd.accept(x, z);
               } else {
                  onRemove.accept(x, z);
               }

               iter.remove();
            }
         }

         return i;
      }
   }

   private int[] generateBoundingHalfCircleDistance(int radius) {
      int[] ret = new int[radius * 2 + 1];

      for (int i = -radius; i <= radius; i++) {
         ret[i + radius] = (int)Math.sqrt(radius * radius - i * i);
      }

      return ret;
   }


   public interface IUpdateConsumer {
      void accept(int var1, int var2);
   }
}
