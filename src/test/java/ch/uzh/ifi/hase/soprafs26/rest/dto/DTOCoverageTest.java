package ch.uzh.ifi.hase.soprafs26.rest.dto;

import org.junit.jupiter.api.Test;
import pl.pojo.tester.api.assertion.Assertions;

class DtoCoverageTest {

    @Test
    void testAllDtosGettersAndSetters() {
        // Die Library erstellt automatisch Instanzen, befüllt sie und testet die Getter/Setter.
        Assertions.assertPojoMethodsFor(CaboInviteDecisionDTO.class).quickly();
        Assertions.assertPojoMethodsFor(CaboInvitePendingDTO.class).quickly();
        Assertions.assertPojoMethodsFor(CaboInviteRespondDTO.class).quickly();
        Assertions.assertPojoMethodsFor(CaboInviteSentDTO.class).quickly();
        Assertions.assertPojoMethodsFor(LobbyReadyPatchDTO.class).quickly();
    }
}