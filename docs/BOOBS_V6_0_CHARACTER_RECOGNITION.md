# Boob's V6-0 — named cartoon character recognition

## Required behavior
Recognize named cartoon and comic characters directly from natural-language prompts, without a manual character selector. Resolve common dictation mistakes such as "hammer Simpson" to "Homer Simpson" when context is unambiguous.

Keep each character's distinct original facial geometry, proportions, palette, outfit and linework. For crossovers, do not force all subjects into the app's generic Hanna-Barbera fallback style.

Initial examples:
- Fred Flintstone: broad rounded silhouette, black hair, orange spotted tunic, blue necktie, classic Flintstones cel design.
- Homer Simpson: yellow skin, round bald head with two hairs, large round eyes, white shirt, Simpsons cel design.
- Marge Simpson: yellow skin, tall blue beehive hair, green dress, red necklace, Simpsons cel design.
- Wonder Woman: dark hair, tiara, recognizable superhero comic proportions and costume.
- Scooby-Doo: brown Great Dane, black spots, turquoise collar.

## Implementation
Extend CartoonImagePrompt.build() with a name alias resolver and a per-character visual specification registry. Prioritize the character-specific style over DEFAULT_STYLE when a recognized name is present. Avoid a global style prompt that homogenizes crossovers. Include character-count and scene-consistency constraints.

Prompt-only identity matching is not guaranteed. Where supported by the actual installed generation backend, add per-character reference conditioning and regional masks. Do not advertise such conditioning until tested.

## Acceptance tests
- Name-only prompts correctly identify Fred, Homer, Marge and Wonder Woman.
- A Fred + Homer crossover shows two visually distinct recognizable figures.
- Unrecognized names retain existing generic cartoon behavior.
- Explicit user-specified scene and action are preserved.
- No changes to photo generation or editing pipelines.
- Confirm a passing Android build and visually inspect generated images before releasing an APK.
