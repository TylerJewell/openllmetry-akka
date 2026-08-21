package io.akka.openllmetry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** SPEC-001 rules 4 and 5 — question-log rows 12, 13, 14. */
public class ProviderTest {

  @Test
  public void providerComesFromTheBaseUrlBySubstring() {
    assertEquals("openai", Provider.fromBaseUrl("https://api.openai.com/v1"));
    assertEquals("azure.ai.openai", Provider.fromBaseUrl("https://my-res.openai.azure.com/v1"));
    assertEquals(
        "aws.bedrock", Provider.fromBaseUrl("https://bedrock-runtime.us-east-1.amazonaws.com/v1"));
    assertEquals("gcp.vertex_ai", Provider.fromBaseUrl("https://generativelanguage.googleapis.com/v1"));
    assertEquals("openrouter", Provider.fromBaseUrl("https://openrouter.ai/api/v1"));
  }

  @Test
  public void anUnmatchedOrEmptyBaseUrlIsOpenai() {
    assertEquals("openai", Provider.fromBaseUrl("https://llm.internal.example/v1"));
    assertEquals("openai", Provider.fromBaseUrl(""));
    assertEquals("openai", Provider.fromBaseUrl(null));
  }

  @Test
  public void azureIsTestedBeforeTheOpenaiDefault() {
    // The Azure host contains "openai" too; order of the tests is what decides this one.
    assertEquals("azure.ai.openai", Provider.fromBaseUrl("https://x.openai.azure.com/"));
  }

  @Test
  public void theResponseModelAlwaysLosesAProviderPrefix() {
    assertEquals("gpt-4o-2024-08-06", Provider.responseModel("openai/gpt-4o-2024-08-06"));
    assertEquals("gpt-4o", Provider.responseModel("gpt-4o"));
    assertEquals("claude-3-sonnet", Provider.responseModel("anthropic/claude-3-sonnet"));
  }

  @Test
  public void theRequestModelKeepsItsPrefixUnderEveryProviderButOpenrouter() {
    assertEquals("openai/gpt-4o", Provider.requestModel("openai", "openai/gpt-4o"));
    assertEquals("openai/gpt-4o", Provider.requestModel("azure.ai.openai", "openai/gpt-4o"));
    assertEquals("openai/gpt-4o", Provider.requestModel("gcp.vertex_ai", "openai/gpt-4o"));
    assertEquals("gpt-4o", Provider.requestModel("openrouter", "openai/gpt-4o"));
  }

  @Test
  public void bedrockDropsTheRegionPrefixAndTheVendor() {
    assertEquals("claude-3-sonnet", Provider.requestModel("aws.bedrock", "us.anthropic.claude-3-sonnet"));
    assertEquals("claude-3-sonnet", Provider.requestModel("aws.bedrock", "anthropic.claude-3-sonnet"));
    assertEquals("claude-3-sonnet", Provider.requestModel("aws.bedrock", "claude-3-sonnet"));
  }

  @Test
  public void aBedrockRegionPrefixWithNothingAfterTheVendorIsLeftAlone() {
    assertEquals("us.anthropic", Provider.requestModel("aws.bedrock", "us.anthropic"));
  }
}
