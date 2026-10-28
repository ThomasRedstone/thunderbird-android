package net.thunderbird.feature.mail.message.list.domain.model

actual enum class SortType {
    DateAsc,
    DateDesc,
    ArrivalAsc,
    ArrivalDesc,
    SubjectAsc,
    SubjectDesc,
    SenderAsc,
    SenderDesc,
    FlaggedAsc,
    FlaggedDesc,
    UnreadAsc,
    UnreadDesc,
    AttachmentAsc,
    AttachmentDesc,
}
