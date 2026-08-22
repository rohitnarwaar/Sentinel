package com.sentinel.service;

import java.util.List;

/**
 * Synthetic past-case narratives used to seed the pgvector store on first
 * startup. Written as short analyst-style notes covering a spread of fraud
 * patterns plus enough clean/legitimate examples that retrieval doesn't
 * always skew toward "this is fraud" — an investigation agent needs
 * negative examples too.
 */
final class SeedCaseNotes {

    private SeedCaseNotes() {
    }

    record SeedNote(String narrative, String label) {
    }

    static final List<SeedNote> ALL = List.of(
            // --- card testing ---
            new SeedNote("Card ending 4471 was charged eleven times in six minutes at small online merchants, each under $5, before a $1,200 charge went through at an electronics retailer. Classic card-testing pattern — low-value probes to validate a stolen card number before a high-value purchase.", "card_testing"),
            new SeedNote("Eight micro-transactions between $0.50 and $2.00 hit different subscription services within ninety seconds, all declined except two. Account had no prior subscription activity. Consistent with automated card-testing against a list of stolen numbers.", "card_testing"),
            new SeedNote("A newly added card was used for six rapid small-dollar purchases at six different merchants in under three minutes, none related by category. No purchases followed for 48 hours after. Bot-driven validation sweep, card likely abandoned once confirmed live.", "card_testing"),
            new SeedNote("Series of $1 authorization holds (no capture) placed against the account at four separate gas station terminals within two minutes — a common technique fraudsters use to test card validity without completing a purchase.", "card_testing"),
            new SeedNote("Twelve failed authorizations for identical $9.99 amounts across twelve different small e-commerce sites within four minutes, originating from the same device fingerprint. Card was reported stolen the following day.", "card_testing"),
            new SeedNote("Unusual burst of five sub-$3 transactions at unrelated merchants (coffee shop, app store, parking app, vending machine, streaming trial) within ninety seconds, followed by silence. Amounts too small and merchants too unrelated to reflect normal spending.", "card_testing"),
            new SeedNote("Card was tested with a $0.01 donation charge to three different charity sites in rapid succession, a known low-friction way to confirm a card number is live before larger fraudulent use.", "card_testing"),
            new SeedNote("Seven transactions under $5 each, all at different digital goods marketplaces, all within five minutes of card creation. No purchase history existed before this burst. Flagged and card suspended pending review.", "card_testing"),
            new SeedNote("Rapid-fire small charges (four transactions, $1-$4 range, ninety seconds total) at merchant categories with historically high fraud-testing rates (prepaid top-ups, gift card sites). Account otherwise dormant for six months.", "card_testing"),

            // --- account takeover ---
            new SeedNote("Account password and registered email were changed eleven minutes before a $3,400 wire-adjacent purchase at an electronics retailer the account had never used. Login originated from a device and IP never seen on this account before.", "account_takeover"),
            new SeedNote("Customer's mobile number was changed via account settings, immediately followed by a request to reset the account password, followed by three high-value purchases within the hour. Customer later confirmed they never made this request.", "account_takeover"),
            new SeedNote("Login occurred from a new device in a different country, immediately followed by the shipping address being changed to an address with no prior association to the account, then a $2,100 purchase. Strong account-takeover signature.", "account_takeover"),
            new SeedNote("Multiple failed login attempts over two hours preceded a successful login from a new IP, after which the account's two-factor phone number was swapped and a large purchase was attempted minutes later.", "account_takeover"),
            new SeedNote("Session token reused from a known credential-stuffing IP range. Account showed no prior activity from that geography. A $4,800 purchase was attempted 90 seconds after login and blocked by velocity rules.", "account_takeover"),
            new SeedNote("Account email was changed to a throwaway domain, then a password reset was completed, then billing address updated to match a new shipping address in a different state — all within four minutes, followed by a high-value order.", "account_takeover"),
            new SeedNote("Customer reported they received a password-reset email they did not request. Nine minutes later the account attempted a $1,950 purchase shipped to an address the customer has never used.", "account_takeover"),
            new SeedNote("Device fingerprint and browser locale changed mid-session without a logout/login cycle, consistent with a session-hijacking tool. A large purchase attempt followed within the same session.", "account_takeover"),
            new SeedNote("Account's saved payment method was swapped for a new card immediately after a login from an unfamiliar ASN, then used for a purchase exceeding the account's typical spend by 15x within the hour.", "account_takeover"),

            // --- geographic anomaly ---
            new SeedNote("Transaction occurred in Lagos, Nigeria eighteen minutes after a transaction in Chicago on the same card — a physically impossible travel time, indicating either card cloning or a compromised card number used remotely.", "geographic_anomaly"),
            new SeedNote("Card used at a point-of-sale terminal in Bangkok while the cardholder's mobile app showed them actively logged in from New York at the same timestamp. Physically impossible dual presence.", "geographic_anomaly"),
            new SeedNote("Account with two years of exclusively domestic US transaction history suddenly shows a $2,300 purchase from a merchant in Moscow with no prior international activity or travel notification on file.", "geographic_anomaly"),
            new SeedNote("Three transactions in three different countries (Germany, Vietnam, Brazil) within a single four-hour window, none preceded by a travel notification, none matching the cardholder's known home country.", "geographic_anomaly"),
            new SeedNote("IP geolocation for the session (Eastern Europe) does not match the billing or shipping country (United States) and does not match any prior session location for this account in eighteen months of history.", "geographic_anomaly"),
            new SeedNote("Card physically swiped in Manila the same afternoon the cardholder used it for a grocery purchase in Ohio — an eighteen-hour flight distance covered in under three hours. Almost certainly a skimmed/cloned card.", "geographic_anomaly"),
            new SeedNote("Transaction country flagged as high-risk jurisdiction with no prior account history there, no travel notification, and a purchase amount well above the account's typical spend — geography alone was the primary signal.", "geographic_anomaly"),
            new SeedNote("Card used twice within the hour: once at a US gas station and once at an ATM in a country over 6,000 miles away. Bank's velocity-by-distance rule triggered automatically.", "geographic_anomaly"),
            new SeedNote("Cardholder's registered address and every prior transaction is in the Pacific Northwest; this transaction posted from a merchant terminal in a country the cardholder has no travel history with per prior notifications.", "geographic_anomaly"),

            // --- merchant collusion ---
            new SeedNote("Same merchant ID processed eighteen refund-then-repurchase cycles for the same account over three weeks, each cycle just under the manual-review threshold. Pattern consistent with merchant-side collusion to launder value.", "merchant_collusion"),
            new SeedNote("Merchant flagged for an unusually high ratio of chargebacks-to-sales (31%) concentrated among a small cluster of accounts that also share overlapping billing addresses — suggests a collusive ring rather than independent fraud.", "merchant_collusion"),
            new SeedNote("Transaction routed through a merchant that was onboarded four days ago, has no other transaction history, and processed a single $6,000 charge before going dormant — classic shell-merchant pattern.", "merchant_collusion"),
            new SeedNote("Several unrelated cardholders each made a single identical $499.00 purchase at the same small merchant within the same hour, then each disputed the charge as unrecognized a week later — indicates merchant complicity.", "merchant_collusion"),
            new SeedNote("Merchant repeatedly processes authorization-only transactions that are never captured, then issues manual invoices outside the platform — a known technique to disguise fraudulent billing from automated monitoring.", "merchant_collusion"),
            new SeedNote("Cluster of five accounts, all opened within the same week using slightly varied identity details, all transacted exclusively with one merchant before being abandoned — consistent with a merchant running synthetic accounts for itself.", "merchant_collusion"),
            new SeedNote("Merchant's settlement account and one of its 'customers' resolve to the same bank routing number, discovered during manual review after a spike in disputed transactions from that merchant.", "merchant_collusion"),
            new SeedNote("A previously low-volume merchant processed 40x its normal daily transaction count in a single day, all from cards with no prior relationship to the merchant's product category — inconsistent with organic growth.", "merchant_collusion"),

            // --- false positive: legitimate travel ---
            new SeedNote("Customer had submitted a travel notification for the exact dates and countries (France, then Italy) where these transactions occurred; spending pattern matches typical tourist activity — hotel, restaurants, transit. Reviewed and dismissed.", "false_positive_travel"),
            new SeedNote("Sudden international transactions matched a calendar entry synced to the account for a business conference abroad; amounts (hotel, taxi, conference registration) were consistent with the stated purpose. Confirmed legitimate by cardholder.", "false_positive_travel"),
            new SeedNote("Account showed transactions in three cities across two countries within four days, but the cardholder confirmed this via app notification as an approved multi-city vacation itinerary booked six weeks in advance.", "false_positive_travel"),
            new SeedNote("First-time international transaction flagged by geography rule, but customer had used the same card at duty-free at their departure airport hours earlier, establishing a clear, plausible travel sequence. Dismissed as legitimate.", "false_positive_travel"),
            new SeedNote("Frequent business traveler's account triggered a velocity flag from four countries in one week; historical pattern over the past two years shows this is normal for this cardholder's job. No further action needed.", "false_positive_travel"),
            new SeedNote("High-value hotel and car rental charges abroad matched an itinerary the customer had proactively shared with support after losing access to travel notifications; charges were confirmed as expected and legitimate.", "false_positive_travel"),
            new SeedNote("Transaction flagged for geographic anomaly, but customer's phone GPS (with location sharing enabled) placed them in the same city as the merchant at the time of purchase. Strong corroborating signal of legitimacy.", "false_positive_travel"),
            new SeedNote("Cardholder had called ahead to notify of an upcoming honeymoon trip; subsequent transactions across two countries matched the stated itinerary closely enough that the case was closed without contacting the customer again.", "false_positive_travel"),
            new SeedNote("Student studying abroad for a semester triggered repeated geography flags; account notes from three months prior already document the study-abroad program and expected country, so the pattern was expected.", "false_positive_travel"),

            // --- synthetic identity ---
            new SeedNote("Account was opened four months ago using a valid Social Security number paired with a name and date of birth that don't match any existing credit history — a hallmark of synthetic identity fraud maturing before a 'bust-out'.", "synthetic_identity"),
            new SeedNote("Credit profile shows a thin file suddenly gaining several accounts in a short window, all opened with slightly different variations of the same address, consistent with identity fabrication rather than a single real consumer.", "synthetic_identity"),
            new SeedNote("Account activity was minimal and unremarkable for eight months (a common synthetic-identity aging strategy) before a sudden maximum-limit cash advance and disappearance — consistent with a bust-out scheme.", "synthetic_identity"),
            new SeedNote("Identity verification service flagged that the phone number and address on file have been associated with four other recently opened accounts under different names, suggesting a fabricated-identity ring.", "synthetic_identity"),
            new SeedNote("Applicant's SSN was issued in a year inconsistent with the stated date of birth range, a common technical marker for synthetic identities built from a mix of real and invented data.", "synthetic_identity"),
            new SeedNote("Account showed a slow, deliberate credit-building pattern (small purchases, always paid on time) for nearly a year before maxing out every available credit line within 48 hours — textbook bust-out behavior.", "synthetic_identity"),
            new SeedNote("Multiple accounts across the institution share the same device fingerprint and mailing address but use entirely different names and dates of birth — a synthetic-identity farm rather than isolated fraud.", "synthetic_identity"),

            // --- refund / return abuse ---
            new SeedNote("Customer requested full refunds on eleven of their last twelve orders, each time citing 'item not as described,' while keeping and reselling the merchandise per marketplace listings traced to the same shipping name.", "refund_abuse"),
            new SeedNote("Pattern of purchasing high-value items, wearing/using them, then returning with a fabricated defect claim just before the return window closes — repeated six times over four months across different product categories.", "refund_abuse"),
            new SeedNote("Account requested a refund claiming non-delivery for a package that tracking data confirms was delivered and signed for at the billing address — a common 'friendly fraud' refund abuse pattern.", "refund_abuse"),
            new SeedNote("Customer disputes a charge as unauthorized while continuing to use the subscription service actively for the following three months — inconsistent with a genuine unauthorized-use claim.", "refund_abuse"),
            new SeedNote("Serial returner flagged: returns 68% of all purchases within the platform's return window, well above the 8% average, frequently swapping the returned item for a lower-value counterfeit before shipping it back.", "refund_abuse"),
            new SeedNote("Customer filed a chargeback for 'item never received' on a digital download product that has no physical shipment and was accessed and downloaded successfully per server logs.", "refund_abuse"),
            new SeedNote("Recurring pattern: purchase premium electronics with express shipping, file an 'arrived damaged' claim with a stock photo as evidence, receive a replacement, then resell the original — observed across three separate orders.", "refund_abuse"),

            // --- legitimate / clean ---
            new SeedNote("Routine grocery and gas station spending consistent with two years of account history; amounts, merchants, and frequency all fall within the account's normal range. No signals of concern.", "legitimate"),
            new SeedNote("Slightly higher than usual purchase at a home improvement store coincided with the customer's documented home renovation project mentioned in a prior support ticket. Reviewed and confirmed legitimate.", "legitimate"),
            new SeedNote("First-time purchase at a new merchant, but amount is modest and consistent with the account's typical spend, and no other risk signals (device, location, velocity) were present. Closed as normal activity.", "legitimate"),
            new SeedNote("Holiday-season spending spike in December matches this account's spending pattern in the same period for each of the past three years — seasonal, not anomalous.", "legitimate"),
            new SeedNote("Recurring subscription renewal charge matched exactly to the amount and merchant the customer has been billed by monthly for over a year. No action needed.", "legitimate"),
            new SeedNote("Large one-time purchase at a furniture retailer was preceded by the customer browsing the merchant's site from the same registered device for several days — a normal pre-purchase research pattern.", "legitimate"),
            new SeedNote("Account's first international transaction was accompanied by a travel notification submitted through the mobile app two days prior, and location data matched the merchant's country. Cleared automatically.", "legitimate"),
            new SeedNote("Paycheck-timed spending pattern (higher transaction volume in the 48 hours after each biweekly deposit) has been consistent for the account's entire eighteen-month history. This period's activity matches that pattern exactly.", "legitimate"),
            new SeedNote("Customer called proactively to notify of a large upcoming purchase for a family event; the subsequent transaction matched the amount and merchant category discussed on the call almost exactly.", "legitimate"),
            new SeedNote("New card was used for a small $4 verification-style purchase at a well-established, reputable merchant the day after activation — normal card-activation behavior, not a testing pattern.", "legitimate"),
            new SeedNote("Elevated velocity (six transactions in one day) was fully explained by a documented one-day shopping trip; all merchants were in the same shopping district and amounts were unremarkable individually.", "legitimate"),
            new SeedNote("Account's spend on dining and entertainment rose modestly over the past quarter, consistent with a gradual, organic increase rather than a sudden anomalous spike. No review action taken.", "legitimate")
    );
}
