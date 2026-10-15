import React, { useEffect } from 'react';
import { Alert, Button, Spin } from '@arco-design/web-react';
import { QRCodeSVG } from 'qrcode.react';
import { useTranslation } from 'react-i18next';
import { useIlinkSession } from '@renderer/services/ilinkSession';

type Props = {
  /** 挂载即自动起二维码：登录页要这样；设置页更愿意让用户点一下。 */
  autoStart?: boolean;
  /** 二维码尺寸（px）。 */
  size?: number;
  /** 登录成功后回调（登录页据此跳转）。 */
  onSignedIn?: () => void;
};

/**
 * 微信扫码登录：唯一的一张二维码界面。
 *
 * 登录流程（取票 → 轮询 → 落盘 → 开推送）都在 useIlinkSession 里，这里只负责画；
 * 登录页和设置页复用同一份状态，所以在哪边扫码，两边同时变成已登录。
 */
export default function IlinkQrLogin({ autoStart = false, size = 208, onSignedIn }: Props) {
  const { t } = useTranslation();
  const { phase, qr, status, error, startLogin, refreshLogin, cancelLogin, scanning } = useIlinkSession();

  useEffect(() => {
    if (autoStart) startLogin();
  }, [autoStart, startLogin]);

  useEffect(() => {
    if (phase === 'signedIn') onSignedIn?.();
  }, [phase, onSignedIn]);

  const waiting = status === 'pending' || status === null;

  return (
    <div className='flex flex-col items-center gap-12px' data-testid='ilink-qr-login'>
      <div className='text-13px text-t-secondary'>{t('login.ilink.scanHint')}</div>

      {qr ? (
        <div className='p-16px bg-white rounded-8px' style={{ lineHeight: 0 }}>
          <QRCodeSVG value={qr.content} size={size} level='M' />
        </div>
      ) : (
        <div className='flex items-center justify-center' style={{ width: size, height: size }}>
          <Spin />
        </div>
      )}

      {scanning ? (
        <div className='text-12px text-t-tertiary' role='status' aria-live='polite'>
          {waiting ? t('login.ilink.waiting') : t('login.ilink.status', { status: status ?? '' })}
        </div>
      ) : null}

      {error ? (
        <Alert type='error' style={{ width: '100%' }} content={t('login.ilink.errorDetail', { detail: error })} />
      ) : null}

      <div className='flex items-center gap-8px'>
        {scanning ? (
          <>
            <Button size='mini' onClick={() => refreshLogin()}>
              {t('login.ilink.refresh')}
            </Button>
            <Button size='mini' type='secondary' onClick={cancelLogin}>
              {t('login.ilink.cancel')}
            </Button>
          </>
        ) : (
          <Button size='mini' type='primary' onClick={() => startLogin()}>
            {/* 第一次是「扫码连接」，失败之后才是「重新扫码」。 */}
            {error ? t('login.ilink.retry') : t('login.ilink.start')}
          </Button>
        )}
      </div>
    </div>
  );
}
