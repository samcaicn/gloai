import React from 'react';
import { useTranslation } from 'react-i18next';
import IlinkQrLogin from '@renderer/components/ilink/IlinkQrLogin';
import { useIlinkSessionOptional } from '@renderer/services/ilinkSession';
import styles from './Welcome.module.css';

/**
 * 引导里「连微信」这一步的内容。
 *
 * 它是可选的，所以一步之内要把话说全：连了有什么用、不连也没事。登录态是全局的，
 * 这里扫出来的结果和设置页看到的是同一份。
 *
 * 用 optional 那个 hook 而不是 useIlinkSession：这块也在单测里被直接渲染，那时
 * 并没有 provider；读不到登录态就只留一句「去设置里连」，不必为了一张二维码
 * 把整页炸掉。
 */
export default function IlinkStep() {
  const { t } = useTranslation();
  const ilink = useIlinkSessionOptional();

  if (!ilink) {
    return <p className={styles.note}>{t('mu.welcome.ilink.unavailable')}</p>;
  }

  if (ilink.phase === 'signedIn') {
    return (
      <div className={styles.ilink}>
        <p className={styles.note} data-testid='mu-welcome-ilink-connected'>
          {t('mu.welcome.ilink.connected')}
        </p>
      </div>
    );
  }

  return (
    <div className={styles.ilink}>
      <IlinkQrLogin autoStart size={200} />
      <p className={styles.note}>{t('mu.welcome.ilink.later')}</p>
    </div>
  );
}
