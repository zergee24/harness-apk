# History rights confirmation v1

The user supplies this record. The builder and processing Agent must never set `userConfirmed=true`, `distributionAllowed=true`, or `semanticProcessingApproved=true` on the user's behalf.

```json
{
  "type": "hwiki-rights-confirmation",
  "schemaVersion": 1,
  "purpose": "private-local-install",
  "distributionAllowed": false,
  "sources": [
    {
      "sourceId": "twenty-four-histories",
      "gitRevision": "<revision from source-lock.json>",
      "userConfirmed": true,
      "basis": "<user-provided basis>",
      "distributionAllowed": false,
      "semanticProcessingApproved": false,
      "evidence": []
    },
    {
      "sourceId": "zizhi-tongjian",
      "gitRevision": "<revision from source-lock.json>",
      "userConfirmed": true,
      "basis": "<user-provided basis>",
      "distributionAllowed": false,
      "semanticProcessingApproved": false,
      "evidence": []
    }
  ]
}
```

Serialize the final user-supplied object as UTF-8 canonical JSON: keys sorted, no insignificant whitespace, no trailing newline. `basis` must be nonblank. Private local installation does not require distribution evidence. Distribution requires both top-level and per-source distribution flags plus a nonempty evidence path or URL for every source.

`semanticProcessingApproved` is separate from local installation. Set it to true only after the user approves sending bounded job text to the currently configured Codex service.
