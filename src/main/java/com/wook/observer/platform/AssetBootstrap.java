package com.wook.observer.platform;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!collector")
public class AssetBootstrap implements ApplicationRunner {
    private final Store store;
    private final Assets assets;
    public AssetBootstrap(Store store, Assets assets) { this.store = store; this.assets = assets; }

    @Override
    public void run(ApplicationArguments args) {
        long started = System.nanoTime();
        // Upgrade existing installations one snapshot at a time, without rescanning or reassessing reports.
        var rows = store.rows("""
                SELECT j.id FROM scan_job j WHERE j.status='COMPLETED' AND j.report_json IS NOT NULL
                AND j.id=(SELECT z.id FROM scan_job z WHERE z.target_id=j.target_id AND z.status='COMPLETED'
                    ORDER BY z.created_at DESC, z.id DESC LIMIT 1)
                AND NOT EXISTS (SELECT 1 FROM asset_inventory i WHERE i.target_id=j.target_id AND i.job_id=j.id)
                """);
        for (var row : rows) assets.backfill(Store.string(row, "id"));
        int identities = assets.enrichOsIdentity();
        OperationLog.info("asset.backfill.completed", "assets", rows.size(), "osIdentities", identities, "durationMs", OperationLog.elapsed(started));
    }
}
