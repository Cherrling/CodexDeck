package cc.cherr.codexdeck.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(tableName = "known_hosts", primaryKeys = {"hostname", "port"})
public class KnownHostRecord {
    @NonNull public String hostname = "";
    public int port;
    @NonNull public String algorithm = "";
    @NonNull public String fingerprint = "";
    public long firstSeenAt = 0;
    public long lastVerifiedAt = 0;
}
