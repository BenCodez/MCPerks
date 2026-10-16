package com.bencodez.mcperks.userapi;
import java.nio.file.Path;
import com.bencodez.mcperks.data.DurableFeatureFiles;
public final class UsageChargesFixture {
    private UsageChargesFixture() {}
    public static UsageCharges create(Path file, DurableFeatureFiles.Publisher publisher) { return new UsageCharges(file, publisher); }
}
