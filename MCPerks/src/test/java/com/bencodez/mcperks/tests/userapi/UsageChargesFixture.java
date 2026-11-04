package com.bencodez.mcperks.tests.userapi;
import com.bencodez.mcperks.userapi.UsageCharges;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import com.bencodez.mcperks.data.DurableFeatureFiles;
/** Narrow constructor access, independent of plugin/SDK test fixtures. */
public final class UsageChargesFixture {
    private UsageChargesFixture() {}
    public static UsageCharges create(Path file, DurableFeatureFiles.Publisher publisher) {
        try {
            var ctor = UsageCharges.class.getDeclaredConstructor(Path.class, DurableFeatureFiles.Publisher.class);
            ctor.setAccessible(true);
            return ctor.newInstance(file, publisher);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException failure) throw failure;
            if (error.getCause() instanceof Error failure) throw failure;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
