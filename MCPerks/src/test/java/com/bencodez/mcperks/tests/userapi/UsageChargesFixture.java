package com.bencodez.mcperks.tests.userapi;
import static com.bencodez.mcperks.tests.TestAccess.*;
import com.bencodez.mcperks.userapi.*;
import java.nio.file.Path;
import com.bencodez.mcperks.data.DurableFeatureFiles;
public final class UsageChargesFixture {
    private UsageChargesFixture() {}
    public static UsageCharges create(Path file, DurableFeatureFiles.Publisher publisher) { return newUsageCharges(file, publisher); }
}
