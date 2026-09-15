# IAP purchase + founding-slot integrity

Why a founding-member slot is only consumed by a **verified, paid** order, and
what has to be true on the server for that to hold.

## The rule

> A founding-member slot is consumed **only** by a purchase Huawei has
> confirmed, and only for the account that paid. A failed, cancelled, abandoned
> or refunded purchase consumes nothing, and the entitlement is granted only on
> a successful purchase.

## What was wrong

`claim_founding_member` took the client's word for it. Its only inputs were the
user id, country, plan id and cap — **nothing that proved a payment happened**.
Three concrete ways a slot could be burned without money changing hands:

1. **Client assertion.** The app called the claim from
   `SubscriptionResult.Success`, a value produced entirely on-device by parsing
   the purchase-result `Intent`. A modified client could claim with no purchase.
2. **Empty payload on `ORDER_PRODUCT_OWNED`.** `handlePurchaseResult` also maps
   `ORDER_PRODUCT_OWNED` to `Success`, and that branch can carry an *empty*
   `inAppPurchaseData` (a re-tap of a button for something already owned). Even
   an honest build could therefore consume a slot with no new payment.
3. **Refunds and voids.** A later-refunded order kept its slot permanently.

And because there was no way to tell a real claim from a phantom one, a region's
remaining count could be wrong in either direction.

## How it works now

```
SubscriptionBilling.handlePurchaseResult
  └─ ORDER_STATE_SUCCESS / ORDER_PRODUCT_OWNED
       └─ AppViewModel.onSubscriptionPurchaseResult
            ├─ grantSubscription(productId)                     ← entitlement
            └─ only if purchaseData is NON-BLANK:
                 SupabaseSync.recordFoundingMemberClaim(planId, purchaseData, signature)
                   └─ POST founding-member-eligibility
                        ├─ Gate 1: plan_id AND payload productId must be a founding plan
                        ├─ Gate 2: huaweiOrder.ts verifies the token with Huawei
                        │            verified → carry Huawei's order_id + purchase_state
                        │            rejected → 402, nothing written
                        │            unavailable → 503, nothing written  (fail CLOSED)
                        └─ claim_founding_member(..., p_verified, p_order_id,
                                                 p_purchase_token, p_purchase_state)
                             └─ refuses to INSERT unless verified AND purchase_state = 0
```

**Three gates, and the last one is in the database**, so no client can reach
around it.

### Deliberate decisions

- **A blank payload is not claimed at all.** `ORDER_PRODUCT_OWNED` with no
  signed data is a *restore*, not a payment. The entitlement is still granted
  (the store says the user owns it), but no slot is touched.
- **Verification failure fails closed.** "Couldn't reach Huawei" and "Huawei
  refused" are different diagnoses with the same outcome for a scarce slot: no
  slot is consumed. A slot cannot be handed back, so the safe direction is to
  refuse and reconcile.
- **`charged`, not `claimed`, is what the UI keys on.** `claimed` is also true
  when the server re-reads an *existing* claim (that idempotency is what makes a
  retry safe), so only `charged` means "this call consumed a slot".
- **The old 4-argument `claim_founding_member` is dropped, not left in place.**
  Postgres identifies functions by name *and* argument types; keeping it would
  leave a second, unverified entry point callable by anyone who found it.
- **Existing rows are grandfathered, not deleted.** They came through the
  store-success path, so they represent real orders the server simply had no way
  to verify. They are marked `verification_method = 'legacy_client_asserted'` —
  recorded honestly rather than presented as proof. Deleting them would free
  slots that were legitimately sold and let a region exceed its advertised cap.
- **The count only counts verified rows**, so the display cannot promise a slot
  the claim path would refuse.
- **One paid order cannot buy two slots**: partial unique indexes on `order_id`
  and `purchase_token` (partial, so the grandfathered rows with neither are
  untouched).

### Tips are treated differently, on purpose

A tip is not scarce, so `tip-purchase` records an unverifiable tip as
`verified = false` rather than discarding it — the user did pay. Only a
definitive refusal from Huawei (`rejected`) is never recorded. The Huawei call
itself lives in `_shared/huaweiOrder.ts` so the two functions cannot drift into
disagreeing about what a verified purchase is.

## Operator notes

- **Secrets required for slot consumption.** `HUAWEI_IAP_CLIENT_ID` and
  `HUAWEI_IAP_CLIENT_SECRET` are Supabase Edge Function secrets. Without them
  every claim returns `503 verification_unavailable` — no slot is consumed —
  and every tip is recorded as unverified. Set them before selling founding
  plans:
  ```
  supabase secrets set HUAWEI_IAP_CLIENT_ID=<AGC OAuth client id> \
                       HUAWEI_IAP_CLIENT_SECRET=<AGC OAuth client secret>
  ```
- **Migrations to apply** (in order):
  `20260916100000_founding_claim_requires_verified_purchase.sql`.
- **Two mirrored function trees.** `supabase/functions/` and
  `backend/functions/` are byte-identical copies; change both or the deployed
  copy depends on which tree the deploy reads.
- **Deploy shape.** `supabase functions deploy founding-member-eligibility`,
  `... tip-purchase` — `_shared/` is bundled automatically for each function
  that imports it.

## Not verifiable without a device and a live account

A real purchase, a real cancellation, a real failed payment, a real refund, and
Huawei's live response shape for the subscription verify path. The exact
subscription verify path is the main unknown: `huaweiOrder.ts` tries all known
shapes and a wrong guess costs one request (404 ⇒ next), but only a live
purchase confirms which one Huawei answers on.
