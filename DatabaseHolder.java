package com.my.mealse.messenger.data;

import android.content.Context;
import com.my.mealse.util.MealseLogger;

public final class DatabaseHolder {
  private static final String TAG = "MessengerDb";
  private static volatile MealseDatabase instance;
  private static final Object LOCK = new Object();

  private DatabaseHolder() {
  }

  public static MealseDatabase get(Context context) {
    if (instance != null)
      return instance;
    synchronized (LOCK) {
      if (instance != null)
        return instance;
      Context appCtx = context.getApplicationContext();
      MealseLogger log = MealseLogger.get(appCtx, TAG);
      log.log("DatabaseHolder: создаю Room-базу " + MealseDatabase.DB_NAME);

      // Используем наш ручной класс вместо Room.databaseBuilder
      instance = MealseDatabaseImpl.create(appCtx);
      // вместо new MealseDatabaseImpl(appCtx)

      log.log("DatabaseHolder: база создана");
      return instance;
    }
  }

  public static void close() {
    synchronized (LOCK) {
      if (instance != null) {
        try {
          instance.close();
        } catch (Exception ignored) {
        }
        instance = null;
      }
    }
  }

  public static boolean isInitialized() {
    return instance != null;
  }
}     return instance;
            }
            Context appCtx = context.getApplicationContext();
            MealseLogger log = MealseLogger.get(appCtx, TAG);
            log.log("DatabaseHolder: создаю Room-базу " + MealseDatabase.DB_NAME);

            instance = Room.databaseBuilder(
                    appCtx,
                    MealseDatabase.class,
                    MealseDatabase.DB_NAME
                )
                .fallbackToDestructiveMigration() // для MVP: при смене схемы — пересоздание
                .build();

            log.log("DatabaseHolder: база создана");
            return instance;
        }
    }

    /**
     * Закрыть базу. Вызывать только при завершении процесса
     * (MealseApp.onTerminate) или в тестах.
     */
    public static void close() {
        synchronized (LOCK) {
            if (instance != null) {
                try {
                    instance.close();
                } catch (Exception ignored) {
                }
                instance = null;
            }
        }
    }

    /** Только для диагностики. */
    public static boolean isInitialized() {
        return instance != null;
    }
}