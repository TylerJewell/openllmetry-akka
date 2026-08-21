package io.akka.openllmetry.domain;

/**
 * SPEC-001 rules 4 and 5 — which vendor a base URL names, and the asymmetry between how the
 * request model and the response model are normalised.
 */
public final class Provider {

  public static final String OPENAI = "openai";
  public static final String AZURE = "azure.ai.openai";
  public static final String BEDROCK = "aws.bedrock";
  public static final String VERTEX = "gcp.vertex_ai";
  public static final String OPENROUTER = "openrouter";

  /** The Azure host contains "openai", so the order of these tests is what decides it. */
  public static String fromBaseUrl(String baseUrl) {
    if (baseUrl == null || baseUrl.isEmpty()) return OPENAI;
    if (baseUrl.contains("openai.azure.com")) return AZURE;
    if (baseUrl.contains("amazonaws.com") || baseUrl.contains("bedrock")) return BEDROCK;
    if (baseUrl.contains("googleapis.com") || baseUrl.contains("vertex")) return VERTEX;
    if (baseUrl.contains("openrouter.ai")) return OPENROUTER;
    return OPENAI;
  }

  /** Always loses a leading {@code provider/} segment, whoever the provider is. */
  public static String responseModel(String model) {
    return stripProviderPrefix(model);
  }

  /** Normalised only under two providers; written exactly as given under the rest. */
  public static String requestModel(String provider, String model) {
    if (model == null) return null;
    if (BEDROCK.equals(provider)) return bedrock(model);
    if (OPENROUTER.equals(provider)) return stripProviderPrefix(model);
    return model;
  }

  private static String stripProviderPrefix(String model) {
    if (model == null) return null;
    int slash = model.lastIndexOf('/');
    return slash < 0 ? model : model.substring(slash + 1);
  }

  private static String bedrock(String model) {
    int firstDot = model.indexOf('.');
    if (firstDot < 0) return model;
    for (var prefix : new String[] {"us", "us-gov", "eu", "apac"}) {
      if (model.startsWith(prefix + ".")) {
        int vendorDot = model.indexOf('.', prefix.length() + 1);
        return vendorDot < 0 ? model : model.substring(vendorDot + 1);
      }
    }
    return model.substring(firstDot + 1);
  }

  private Provider() {}
}
