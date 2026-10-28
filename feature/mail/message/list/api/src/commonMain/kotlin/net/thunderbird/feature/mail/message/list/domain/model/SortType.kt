package net.thunderbird.feature.mail.message.list.domain.model

/** Shared sort identities; platform-specific labels remain with the platform. */
expect enum class SortType {
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
