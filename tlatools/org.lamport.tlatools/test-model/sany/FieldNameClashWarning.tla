---- MODULE FieldNameClashWarning ----
\* This spec triggers warning 4802 (RECORD_CONSTRUCTOR_FIELD_NAME_CLASH).
\* The field name 'bar' in the record constructor [bar |-> 42] clashes with
\* the existing definition 'bar == 23'. In TLA+, record field names are
\* always strings, so the record field does NOT take the value of the 'bar'
\* definition.
bar == 23
R == [bar |-> 42]
====
