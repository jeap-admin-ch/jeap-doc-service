package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.OrderHint;

import java.util.List;

/** Between the documentation sets of the domain and their rows. */
final class CustomDocumentationMapper {

    private CustomDocumentationMapper() {
    }

    static CustomSet toDomain(CustomSetEntity entity, List<CustomPageEntity> pages) {
        return new CustomSet(entity.getId(), keyOf(entity), entity.getLabel(), entity.getRevision(),
                entity.getObjectKey(), entity.getSha256(), entity.getSizeInBytes(),
                new CustomProvenance(entity.getSourceRepository(), entity.getSourceRef(),
                        entity.getSourceRevision(), entity.getSourceTimestamp(), entity.getVersion(),
                        entity.getUploadedAt()),
                pages.stream().map(CustomDocumentationMapper::toDomain).toList(),
                hintOf(entity.getOrderPosition(), entity.getOrderDirection(), entity.getOrderReference()));
    }

    static CustomSetKey keyOf(CustomSetEntity entity) {
        return new CustomSetKey(entity.getSite(), entity.getKind(), entity.getSystemName(),
                entity.getName(),
                entity.getSourceFormat(), entity.getTemplate(), entity.getLocation(), entity.getTopic());
    }

    static CustomPage toDomain(CustomPageEntity entity) {
        return new CustomPage(entity.getChapter(), entity.getFileName(), entity.getTitle(),
                entity.getPosition(), entity.isAsset(),
                hintOf(entity.getOrderPosition(), entity.getOrderDirection(), entity.getOrderReference()));
    }

    static CustomPageEntity toEntity(Long setId, CustomPage page) {
        CustomPageEntity entity = new CustomPageEntity();
        entity.setSetId(setId);
        entity.setChapter(page.chapter());
        entity.setFileName(page.fileName());
        entity.setTitle(page.title());
        entity.setPosition(page.position());
        entity.setAsset(page.asset());
        entity.setOrderPosition(page.hint().position());
        entity.setOrderDirection(page.hint().direction());
        entity.setOrderReference(page.hint().reference());
        return entity;
    }

    /** A row's three columns as the one hint they name, or {@link OrderHint#NONE} where all three are null. */
    private static OrderHint hintOf(Double position, OrderHint.Direction direction, String reference) {
        return position == null && direction == null && reference == null
                ? OrderHint.NONE : new OrderHint(position, direction, reference);
    }
}
