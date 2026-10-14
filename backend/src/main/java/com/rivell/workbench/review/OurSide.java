package com.rivell.workbench.review;

import com.rivell.workbench.contracts.PartyRole;

/** Our side of the agreement as one plain sentence, for model states that judge or draft for us. */
public final class OurSide {

    private OurSide() {
    }

    public static String describe(PartyRole role) {
        if (role == null) {
            return "Our side is not recorded.";
        }
        return switch (role) {
            case CUSTOMER -> "We are the customer. The other party supplies the goods, services or software.";
            case SUPPLIER -> "We are the supplier. The other party is our customer.";
            case LICENSEE -> "We are the licensee. The other party licenses the software to us.";
            case LICENSOR -> "We are the licensor. The other party takes a licence from us.";
            case DISCLOSING_PARTY -> "We disclose confidential information. The other party receives it.";
            case RECEIVING_PARTY -> "We receive confidential information. The other party discloses it.";
            case PARTY_A, PARTY_B ->
                    "The agreement is mutual: we both disclose and receive confidential information.";
        };
    }
}
