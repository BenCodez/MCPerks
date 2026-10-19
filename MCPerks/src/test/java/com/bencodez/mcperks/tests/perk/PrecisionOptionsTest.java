package com.bencodez.mcperks.tests.perk;
import com.bencodez.mcperks.perk.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
class PrecisionOptionsTest {
    private InteractionOptions options(String target) { return new InteractionOptions(false,false,16,8,List.of(),List.of(),List.of(),List.of(target)); }
    @Test void persistentAndUnknownTargetsAreRejectedInsteadOfReportingFalseSuppression() {
        for(String target:List.of("MoveSpeed","Potions","TempPermission","Fly","IncreaseMaxHealth","IncreaseStrength","IncreaseLuck","DoubleJump","DoubleJumpUp","Commands","GlobalCommands","Rewards","CureHunger","HealAll","ToolRepair","PrecisionMode","Unknown"))
            assertThrows(IllegalArgumentException.class,()->options(target),target);
    }
    @Test void independentlyConfiguredActionTargetsAndEmptyDefaultsRemainSupported() {
        for(String target:List.of("AutoBlockRefill","VeinMining","RightClickHarvest","AutoPlant","Fortune")) assertEquals(List.of(target),options(target).precisionTargets());
        assertTrue(new InteractionOptions(false,false,16,8,List.of(),List.of(),List.of(),List.of()).precisionTargets().isEmpty());
    }
}
