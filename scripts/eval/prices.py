"""List prices in USD per 1M tokens (input, output), used to put a cost on every eval setup.

Sources: third-party listings that cite the providers' pages, 2026-10 (approximate). Cached-input
discounts are ignored, so every cost here is an upper bound. Local models (Ollama) cost nothing.
"""

PRICES = {
    "gpt-6-luna": (0.10, 0.50),
    "gpt-6-sol": (2.0, 10.0),
    "gpt-6.1-sol": (2.0, 10.0),
    "jev": (0.042, 0.0),  # TypeSafe Jev: input only, output free
    "embeddinggemma": (0.0, 0.0),
}


def price(model):
    """(input, output) price for a model id or a dated variant of it; None when unknown."""
    name = (model or "").lower()
    for key in sorted(PRICES, key=len, reverse=True):
        if name.startswith(key):
            return PRICES[key]
    return None


def cost(model, input_tokens, output_tokens):
    p = price(model)
    if p is None:
        return None
    return input_tokens / 1e6 * p[0] + output_tokens / 1e6 * p[1]
