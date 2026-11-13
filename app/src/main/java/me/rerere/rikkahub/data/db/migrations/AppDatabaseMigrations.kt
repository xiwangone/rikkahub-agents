package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration

/**
 * [me.rerere.rikkahub.data.db.AppDatabase] 的全部手写迁移（自动迁移由 `@Database(autoMigrations=…)` 声明）。
 *
 * 生产建库（DataSourceModule）与测试（导入备份的整链迁移回归）共用这一份清单，
 * 避免测试里另抄一份、日后新增迁移时两边不一致。
 */
internal val APP_DATABASE_MIGRATIONS: Array<Migration> = arrayOf(
    Migration_6_7,
    Migration_11_12,
    Migration_13_14,
    Migration_14_15,
    Migration_15_16,
    Migration_23_24,
    Migration_28_29,
    Migration_29_30,
    Migration_30_31,
    Migration_31_32,
    Migration_32_33,
    Migration_33_34,
    Migration_34_35,
    Migration_35_36,
    Migration_44_45,
    Migration_45_46,
)
