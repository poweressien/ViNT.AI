# Contribution & review workflow (design; tooling not built yet)
1. Contributors submit expression + meaning + example with explicit consent (CC0 or CC-BY choice) and can withdraw later.
2. No personal data, no private chats, no scraped content without license.
3. Records enter as `human_verified=false`, confidence from reviewer votes. Two independent native-speaker reviews -> verified.
4. Synthetic examples always `synthetic=true`; never reported as real conversations.
5. `pidgin-pipeline validate` flags conflicts; reviewers resolve. `export` excludes unverified records by default.
