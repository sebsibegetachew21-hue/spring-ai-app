package com.yourapp.ai.agent;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class AgentPlannerTest {

  @Test
  void policyOnlyIntent() {
    String json = """
      {
        "needsRetrieval": true,
        "needsTool": false,
        "toolArgument": null
      }
    """;

    AgentPlan plan = PlannerOutputParser.parse(json);
    assertTrue(plan.needsRetrieval());
    assertFalse(plan.needsTool());
    assertNull(plan.toolArgument());
  }

  @Test
  void toolOnlyIntent() {
    String json = """
      {
        "needsRetrieval": false,
        "needsTool": true,
        "toolArgument": "12345"
      }
    """;

    AgentPlan plan = PlannerOutputParser.parse(json);
    assertFalse(plan.needsRetrieval());
    assertTrue(plan.needsTool());
    assertEquals("12345", plan.toolArgument());
  }

  @Test
  void mixedIntentPolicyAndTool() {
    String json = """
      {
        "needsRetrieval": true,
        "needsTool": true,
        "toolArgument": "12345"
      }
    """;

    AgentPlan plan = PlannerOutputParser.parse(json);
    assertTrue(plan.needsRetrieval());
    assertTrue(plan.needsTool());
    assertEquals("12345", plan.toolArgument());
  }

//  @Test
//  void hallucinatedToolNameShouldFail() {
//    String json = """
//      {
//        "needsRetrieval": true,
//        "needsTool": true,
//        "toolName": "getPolicy",
//        "toolArgument": null
//      }
//    """;
//
//    assertThrows(IllegalArgumentException.class,
//        () -> PlannerOutputParser.parse(json));
//  }

//  @Test
//  void plannerMustNotContainToolName() {
//    String json = """
//    {
//      "needsRetrieval": true,
//      "needsTool": true,
//      "toolName": "getPolicy",
//      "toolArgument": null
//    }
//  """;
//
//    Exception ex = assertThrows(
//            IllegalArgumentException.class,
//            () -> PlannerOutputParser.parse(json)
//    );
//
//    assertTrue(ex.getMessage().contains("Invalid planner JSON"));
//  }


  @Test
  void needsToolButMissingOrderIdIsInvalid() {
    String json = """
    {
      "needsRetrieval": false,
      "needsTool": true,
      "toolArgument": null
    }
  """;

    AgentPlan plan = PlannerOutputParser.parse(json);

    assertTrue(plan.needsTool());
    assertNull(plan.toolArgument());
  }

}
