package me.cortex.voxy.compat.far;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 仅确认完整写入的模型；准备、失败和半包不改变投递历史。 */
final class FarEntityDelivery {
    private Map<UUID, VehicleData> delivered = Map.of();

    FarEntitySendQueue.Prepared prepare(FarEntityProtocol.PlayerBatch batch) {
        Map<UUID, VehicleData> next = null;
        ArrayList<FarEntityProtocol.PlayerSnapshot> players = null;
        for (int i = 0; i < batch.players().size(); i++) {
            var player = batch.players().get(i);
            var vehicle = player.vehicle();
            if (vehicle == null) continue;
            if (next == null) next = new HashMap<>();
            var previous = this.delivered.get(player.uuid());
            if (previous != null && vehicle.uuid().equals(previous.uuid())
                    && vehicle.entityTypeId().equals(previous.type())
                    && Arrays.equals(vehicle.renderData(), previous.data())) {
                next.put(player.uuid(), previous);
                if (vehicle.renderData().length != 0) {
                    if (players == null) players = new ArrayList<>(batch.players());
                    players.set(i, player.withVehicle(vehicle.withoutRenderData()));
                }
            } else {
                next.put(player.uuid(), new VehicleData(vehicle.uuid(), vehicle.entityTypeId(), vehicle.renderData()));
            }
        }
        var prepared = players == null ? batch : new FarEntityProtocol.PlayerBatch(batch.dimensionKey(), players);
        Map<UUID, VehicleData> confirmed = next == null ? Map.of() : next;
        return new FarEntitySendQueue.Prepared(prepared, () -> this.delivered = confirmed);
    }

    private record VehicleData(UUID uuid, String type, byte[] data) {}
}
