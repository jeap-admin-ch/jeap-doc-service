package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions.Identity;
import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionView;
import java.math.BigDecimal;
import java.util.List;

final class ReactionTestViews {
    static ReactionView orders() {
        return new ReactionView(List.of(new ReactionView.MessageLink("OrdersPaymentAcceptedEvent", "orders",
                "orders-payment-accepted-event", MessageKind.EVENT)),
                List.of(new ReactionView.ComponentLink("orders", "orders-intake", "orders-intake", "orders")),
                List.of(), List.of(new ReactionView.Row(new Identity("REACTION", "2"), "OrdersPaymentAcceptedEvent",
                        "orders-intake", List.of(), BigDecimal.valueOf(12)),
                        new ReactionView.Row(new Identity("REACTION", "3"), null, "orders-intake", List.of(), BigDecimal.valueOf(9252))), true);
    }
}
