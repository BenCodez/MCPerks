package com.bencodez.mcperks.tests;

import java.lang.reflect.InvocationTargetException;
import java.sql.Timestamp;
import java.util.List;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.data.DurableFeatureFiles;
import com.bencodez.mcperks.data.ServerData;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.mcperks.perk.PerkHandler;
import com.bencodez.mcperks.userapi.MCPerksUser;

/** Narrow access to existing package-private test seams without changing production APIs. */
public final class TestAccess {
    private TestAccess() {}



    public static ServerData newServerData(MCPerksMain plugin, DurableFeatureFiles.Publisher publisher) {
        return construct(ServerData.class, new Class<?>[] {MCPerksMain.class, DurableFeatureFiles.Publisher.class}, plugin, publisher);
    }

    public static PerkHandler newPerkHandler(MCPerksMain plugin, List<Perk> perks) {
        return construct(PerkHandler.class, new Class<?>[] {MCPerksMain.class, List.class}, plugin, perks);
    }
    public static void activatePerk(Perk perk, MCPerksUser user, int length, Timestamp timestamp) {
        try {
            var method = Perk.class.getDeclaredMethod("activatePerk", MCPerksUser.class, int.class, Timestamp.class);
            method.setAccessible(true);
            method.invoke(perk, user, length, timestamp);
        } catch (InvocationTargetException error) {
            throw unchecked(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot access existing perk activation seam", error);
        }
    }
    private static <T> T construct(Class<T> type, Class<?>[] parameters, Object... arguments) {
        try {
            var constructor = type.getDeclaredConstructor(parameters);
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException error) {
            throw unchecked(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot access existing " + type.getSimpleName() + " constructor", error);
        }
    }
    // Preserve the original exception type so assertThrows and Mockito observe the same failure.
    private static RuntimeException unchecked(Throwable error) {
        return TestAccess.<RuntimeException>rethrow(error);
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException rethrow(Throwable error) throws T {
        throw (T) error;
    }
}
